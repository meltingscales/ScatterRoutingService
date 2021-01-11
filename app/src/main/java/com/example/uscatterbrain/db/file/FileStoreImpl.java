package com.example.uscatterbrain.db.file;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

public class FileStoreImpl implements FileStore {
    private static final String TAG = "FileStore";
    private final ConcurrentHashMap<Path, OpenFile> mOpenFiles;
    private final Context mCtx;
    private final File USER_FILES_DIR;
    private final File CACHE_FILES_DIR;

    @Inject
    public FileStoreImpl(
            Context context
    ) {
        mOpenFiles = new ConcurrentHashMap<>();
        this.mCtx = context;
        USER_FILES_DIR =  new File(mCtx.getFilesDir(), USER_FILES_PATH);
        CACHE_FILES_DIR =  new File(mCtx.getFilesDir(), CACHE_FILES_PATH);
    }

    @Override
    public Completable deleteFile(File path) {
        return Single.fromCallable(() -> {
            if (!path.exists()) {
                return FileCallbackResult.ERR_FILE_NO_EXISTS;
            }

            if (!close(path)) {
                return FileCallbackResult.ERR_FAILED;
            }

            if(path.delete()) {
                return FileCallbackResult.ERR_SUCCESS;
            } else {
                return FileCallbackResult.ERR_FAILED;
            }
        }).flatMapCompletable(result -> {
            if (result.equals(FileCallbackResult.ERR_SUCCESS)) {
                return Completable.complete();
            } else {
                return Completable.error(new IllegalStateException(result.toString()));
            }
        });
    }

    @Override
    public boolean isOpen(File path) {
        return mOpenFiles.containsKey(path.toPath());
    }

    @Override
    public boolean close(File path) {
        if (isOpen(path)) {
            OpenFile f = mOpenFiles.get(path);
            if (f != null) {
                try {
                    f.close();
                } catch (IOException e) {
                    return false;
                }
                mOpenFiles.remove(path);
            }
        }
        return  true;
    }

    @Override
    public File getCacheDir() {
        if (!CACHE_FILES_DIR.exists()) {
            if (!CACHE_FILES_DIR.mkdirs()) {
                return null;
            }
        }
        return CACHE_FILES_DIR;
    }

    @Override
    public File getUserDir() {
        if (!USER_FILES_DIR.exists()) {
            if (!USER_FILES_DIR.mkdirs()) {
                return null;
            }
        }
        return USER_FILES_DIR;
    }

    @Override
    public File getFilePath(BlockHeaderPacket packet) {
        return new File(getCacheDir(), packet.getAutogenFilename());
    }

    @Override
    public long getFileSize(File path) {
        return path.length();
    }

    @Override
    public Single<OpenFile> open(File path) {
        return Single.fromCallable(() -> {
            OpenFile old = mOpenFiles.get(path.toPath());
            if (old == null) {
                OpenFile f = new OpenFile(path, false);
                mOpenFiles.put(path.toPath(), f);
                return f;
            } else {
                return old;
            }
        });

    }

    private Completable insertSequence(Flowable<BlockSequencePacket> packets, BlockHeaderPacket header, File path) {
        return Single.fromCallable(() -> new FileOutputStream(path))
                .flatMapCompletable(fileOutputStream -> packets
                        .concatMapCompletable(blockSequencePacket -> {
                            if (!blockSequencePacket.verifyHash(header)) {
                                return Completable.error(new IllegalStateException("failed to verify hash"));
                            }
                            return Completable.fromAction(() -> blockSequencePacket.getmData().writeTo(fileOutputStream))
                                    .subscribeOn(Schedulers.io());
                        }));
    }

    @Override
    public Completable insertFile(WifiDirectRadioModule.BlockDataStream stream) {
        final File file = getFilePath(stream.getHeaderPacket());
        Log.v(TAG, "insertFile: " + file);

        return Completable.fromAction(() -> {
            if (!file.createNewFile()) {
                throw new FileAlreadyExistsException("file " + file + " already exists");
            }
        }).andThen(insertSequence(
                stream.getSequencePackets(),
                stream.getHeaderPacket(),
                file
        ));
    }

    @Override
    public Single<List<ByteString>> hashFile(File path, int blocksize) {
        return Single.fromCallable(() -> {
            List<ByteString> r = new ArrayList<>();
            if (!path.exists()) {
                throw new FileAlreadyExistsException("file already exists");
            }

            FileInputStream is = new FileInputStream(path);
            byte[] buf = new byte[blocksize];
            int read;
            int seqnum = 0;

            while((read = is.read(buf)) != -1){
                BlockSequencePacket blockSequencePacket = BlockSequencePacket.newBuilder()
                        .setSequenceNumber(seqnum)
                        .setData(ByteString.copyFrom(buf, 0, read))
                        .build();
                r.add(blockSequencePacket.calculateHashByteString());
                seqnum++;
                Log.e("debug", "hashing "+ read);
            }
            return r;
        }).subscribeOn(Schedulers.io());
    }

    @Override
    public Flowable<BlockSequencePacket> readFile(File path, int blocksize) {
        Log.v(TAG, "called readFile " + path);
        return Flowable.fromCallable(() -> new FileInputStream(path))
                .doOnSubscribe(disp -> Log.v(TAG, "subscribed to readFile"))
                .flatMap(is -> {
                    Flowable<Integer> seq = Flowable.generate(() -> 0, (state, emitter) -> {
                        emitter.onNext(state);
                        return state + 1;
                    });
                    return Flowable.just(is)
                            .zipWith(seq, (fileInputStream, seqnum) -> {
                                return Flowable.fromCallable(() -> {
                                    byte[] buf = new byte[blocksize];
                                    int read;

                                    read = is.read(buf);
                                    return new Pair<>(read, buf);
                                })
                                        .takeWhile(pair -> pair.first != -1)
                                        .map(pair -> {
                                            Log.e("debug", "reading "+ pair.first);
                                            return BlockSequencePacket.newBuilder()
                                                    .setSequenceNumber(seqnum)
                                                    .setData(ByteString.copyFrom(pair.second, 0, pair.first))
                                                    .build();
                                        })
                                        .subscribeOn(Schedulers.io());
                            }).concatMap(result -> result);
                }).doOnComplete(() -> Log.v(TAG, "readfile completed"));
    }
}

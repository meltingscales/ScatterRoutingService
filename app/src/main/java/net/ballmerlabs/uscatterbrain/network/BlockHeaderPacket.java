package net.ballmerlabs.uscatterbrain.network;

import android.util.Log;

import net.ballmerlabs.uscatterbrain.ScatterProto;
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore;
import com.github.davidmoten.rx2.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;
import com.goterl.lazycode.lazysodium.interfaces.Sign;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

/**
 * Wrapper class for protocol buffer blockdata message
 */
public class BlockHeaderPacket implements ScatterSerializable {
    private ScatterProto.BlockData blockdata;
    private final List<ByteString> mHashList;
    private final ByteString mFromFingerprint;
    private final ByteString mToFingerprint;
    private final String extension;
    private byte[] mSignature;
    private final byte[] mApplication;
    private final int mSessionID;
    private boolean mToDisk;
    private boolean endofstream;
    private final int mBlocksize;
    private final String mime;
    private final String filename;
    private UUID luidtag;

    private BlockHeaderPacket(Builder builder) {
        this.mHashList = builder.getHashlist();
        this.endofstream = builder.endofstream;
        this.extension = builder.extension;
        if (builder.getSig() == null) {
            this.mSignature = new byte[Sign.ED25519_BYTES];
        } else {
            this.mSignature = builder.getSig();
        }
        if (builder.getmToFingerprint() != null) {
            this.mToFingerprint = ByteString.copyFrom(builder.getmToFingerprint());
        } else {
            this.mToFingerprint = ByteString.EMPTY;
        }

        if (builder.getmFromFingerprint() != null) {
            this.mFromFingerprint = ByteString.copyFrom(builder.getmFromFingerprint());
        } else  {
            this.mFromFingerprint = ByteString.EMPTY;
        }
        this.mApplication = builder.getApplication();
        this.mSessionID = builder.getSessionid();
        this.mToDisk = builder.getToDisk();
        this.mBlocksize = builder.getBlockSize();
        this.mime = builder.mime;
        final ScatterProto.BlockData.Builder b = ScatterProto.BlockData.newBuilder();
        if (builder.filename == null) {
            this.filename = getAutogenFilename();
            b.setFilenameGone(true);
        } else {
            b.setFilenameVal(builder.filename);
            this.filename = builder.filename;
        }
        regenBlockData();
    }

    public void markEnd() {
        this.endofstream = true;
        regenBlockData();
    }

    public boolean isEndOfStream() {
        return this.endofstream;
    }

    private ByteString sumBytes() {
        ByteString messagebytes = ByteString.EMPTY;
        messagebytes = messagebytes.concat(this.mFromFingerprint);
        messagebytes = messagebytes.concat(this.mToFingerprint);
        messagebytes = messagebytes.concat(ByteString.copyFrom(this.mApplication));
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(this.extension));
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(this.mime));
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(this.filename));
        byte td = 0;
        if (this.mToDisk)
            td = 1;
        ByteString toDiskBytes = ByteString.copyFrom(ByteBuffer.allocate(1).order(ByteOrder.BIG_ENDIAN).put(td).array());
        messagebytes = messagebytes.concat(toDiskBytes);

        for (ByteString hash : this.mHashList) {
            messagebytes = messagebytes.concat(hash);
        }
        return messagebytes;
    }

    /**
     * Verifyed 25519 boolean.
     *
     * @param pubkey the pubkey
     * @return the boolean
     */
    public boolean verifyed25519(byte[] pubkey) {
        if (pubkey.length != Sign.PUBLICKEYBYTES)
            return false;

        ByteString messagebytes = sumBytes();
        return LibsodiumInterface.getSodium().crypto_sign_verify_detached(this.blockdata.getSig().toByteArray(),
                messagebytes.toByteArray(),
                messagebytes.size(),
                pubkey) == 0;
    }

    private void regenBlockData() {
        this.blockdata = ScatterProto.BlockData.newBuilder()
                .setApplicationBytes(ByteString.copyFrom(this.mApplication))
                .setFromFingerprint(this.mFromFingerprint)
                .setToFingerprint(this.mToFingerprint)
                .setTodisk(this.mToDisk)
                .setExtension(this.extension)
                .addAllNexthashes(this.mHashList)
                .setSessionid(this.mSessionID)
                .setBlocksize(this.mBlocksize)
                .setMime(this.mime)
                .setEndofstream(this.endofstream)
                .setSig(ByteString.copyFrom(this.mSignature))
                .build();
    }

    /**
     * Sign ed 25519 boolean.
     *
     * @param secretkey the secretkey
     * @return the boolean
     */
    public boolean signEd25519(byte[] secretkey) {
        if (secretkey.length != Sign.SECRETKEYBYTES)
            return false;

        ByteString messagebytes = sumBytes();

        this.mSignature = new byte[Sign.ED25519_BYTES];
        Pointer p = new PointerByReference(Pointer.NULL).getPointer();
        if (LibsodiumInterface.getSodium().crypto_sign_detached(this.mSignature,
                p, messagebytes.toByteArray(), messagebytes.size(), secretkey) == 0) {
            regenBlockData();
            return true;
        } else {
            return false;
        }
    }

    public String getUserFilename() {
        return filename;
    }

    public String getAutogenFilename() {
        String ext = ScatterbrainDatastore.getDefaultFileName(this) + "." +
                ScatterbrainDatastore.sanitizeFilename(extension);
        Log.e("debug", "getAutogenFilename: " + ext);
        return ext;
    }

    private BlockHeaderPacket(InputStream in) throws IOException {
        this.blockdata = CRCProtobuf.parseFromCRC(ScatterProto.BlockData.parser(), in);
        this.mApplication = blockdata.getApplicationBytes().toByteArray();
        Log.e("debug ", "header nexthashes count" + blockdata.getNexthashesList().size());
        Log.e("debug", "header nexthashes raw count " + blockdata.getNexthashesCount());
        this.mHashList = blockdata.getNexthashesList();
        this.mFromFingerprint = blockdata.getFromFingerprint();
        this.mToFingerprint = blockdata.getToFingerprint();
        this.mSignature = blockdata.getSig().toByteArray();
        this.mToDisk = blockdata.getTodisk();
        this.mSessionID = blockdata.getSessionid();
        this.mBlocksize = blockdata.getBlocksize();
        this.extension = blockdata.getExtension();
        this.endofstream = blockdata.getEndofstream();
        if (blockdata.getFilenameCase().equals(ScatterProto.BlockData.FilenameCase.FILENAME_VAL)) {
            this.filename = blockdata.getFilenameVal();
        } else {
            this.filename = getAutogenFilename();
        }
        this.mime = blockdata.getMime();
    }

    /**
     * Parse from blockheader packet.
     *
     * @param is the is
     * @return the block header packet
     */
    public static Single<BlockHeaderPacket> parseFrom(InputStream is) {
        return Single.fromCallable(() -> new BlockHeaderPacket(is));
    }

    public static Single<BlockHeaderPacket> parseFrom(Observable<byte[]> flowable) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return BlockHeaderPacket.parseFrom(observer).doFinally(observer::close);
    }

    public static Single<BlockHeaderPacket> parseFrom(Flowable<byte[]> flowable) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return parseFrom(observer).doFinally(observer::close);
    }

    @Override
    public void tagLuid(UUID luid) {
        luidtag = luid;
    }

    @Override
    public UUID getLuid() {
        return luidtag;
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            CRCProtobuf.writeToCRC(blockdata, os);
            return os.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public ByteString getByteString() {
        return ByteString.copyFrom(getBytes());
    }

    @Override
    public Completable writeToStream(OutputStream os) {
        return Completable.fromAction(() -> CRCProtobuf.writeToCRC(blockdata, os));
    }

    @Override
    public Flowable<byte[]> writeToStream(int fragsize) {
        return Bytes.from(new ByteArrayInputStream(getBytes()), fragsize);
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return blockdata;
    }

    @Override
    public PacketType getType() {
        return PacketType.TYPE_BLOCKHEADER;
    }

    /**
     * Gets blockdata.
     *
     * @return the blockdata
     */
    public ScatterProto.BlockData getBlockdata() {
        return this.blockdata;
    }

    /**
     * Gets the blocksize
     * @return int blocksize
     */
    public int getBlockSize() {
        return this.blockdata.getBlocksize();
    }

    /**
     * Gets hash.
     *
     * @param seqnum the seqnum
     * @return the hash
     */
    public ByteString getHash(int seqnum) {
        return this.blockdata.getNexthashes(seqnum);
    }

    /**
     * Gets sig.
     *
     * @return the sig
     */
    public ByteString getSig() {
        return ByteString.copyFrom(this.mSignature);
    }

    /**
     * Get application byte [ ].
     *
     * @return the byte [ ]
     */
    public byte[] getApplication() {
        return this.mApplication;
    }

    /**
     * Gets hash list.
     *
     * @return the hash list
     */
    public List<ByteString> getHashList() {
        return mHashList;
    }

    /**
     * Gets from fingerprint.
     *
     * @return the from fingerprint
     */
    public ByteString getFromFingerprint() {
        return mFromFingerprint;
    }

    /**
     * Gets to fingerprint.
     *
     * @return the to fingerprint
     */
    public ByteString getToFingerprint() {
        return mToFingerprint;
    }

    /**
     * Get signature byte [ ].
     *
     * @return the byte [ ]
     */
    public byte[] getSignature() {
        return mSignature;
    }

    /**
     * Gets session id.
     *
     * @return the session id
     */
    public int getSessionID() {
        return mSessionID;
    }

    /**
     * Gets to disk.
     *
     * @return the to disk
     */
    public boolean getToDisk() {
        return mToDisk;
    }

    /**
     * Gets file extension
     * @return file extension
     */
    public String getExtension() {
        return ScatterbrainDatastore.sanitizeFilename(extension);
    }

    /**
     * Sets to disk.
     *
     * @param t the t
     */
    public void setToDisk(boolean t) { this.mToDisk = t; }

    public String getMime() {
        return mime;
    }

    /**
     * New builder builder.
     *
     * @return the builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * The type Builder.
     */
    public static class Builder {
        private boolean todisk;
        private byte[] application;
        private int sessionid;
        public int mBlockSize;
        private byte[] mToFingerprint;
        private byte[] mFromFingerprint;
        private String extension;
        private List<ByteString> hashlist;
        private byte[] mSig;
        private String filename;
        private String mime;
        private boolean endofstream = false;

        /**
         * Instantiates a new Builder.
         */
        public Builder() {
            todisk = false;
            sessionid = -1;
            mBlockSize = -1;
            mime = "application/octet-stream";
        }

        /**
         * Sets the fingerprint for the recipient.
         *
         * @param toFingerprint the to fingerprint
         * @return builder
         */
        public Builder setToFingerprint(byte[] toFingerprint) {
            this.mToFingerprint = toFingerprint;
            return this;
        }

        /**
         * Sets from fingerprint.
         *
         * @param fromFingerprint sets the fingerprint for the sender
         * @return builder
         */
        public Builder setFromFingerprint(byte[] fromFingerprint) {
            this.mFromFingerprint = fromFingerprint;
            return this;
        }

        /**
         * Sets application.
         *
         * @param application bytes for UTF encoded scatterbrain application string
         * @return builder
         */
        public Builder setApplication(byte[] application) {
            this.application = application;
            return this;
        }

        /**
         * Sets to disk.
         *
         * @param toDisk whether to write this file to disk or attempt to store it in the database
         * @return builder
         */
        public Builder setToDisk(boolean toDisk) {
            this.todisk = toDisk;
            return this;
        }

        /**
         * Sets session id.
         *
         * @param sessionID the session id (used for upgrading between protocols)
         * @return builder
         */
        public Builder setSessionID(int sessionID) {
            this.sessionid = sessionID;
            return this;
        }

        /**
         * Sets hashes.
         *
         * @param hashes list of hashes of following blocksequence packets.
         * @return builder
         */
        public Builder setHashes(List<ByteString> hashes) {
            this.hashlist = hashes;
            return this;
        }


        /**
         * Sets the file extension
         * @param ext: string file extension
         * @return builder
         */
        public Builder setExtension(String ext) {
            this.extension = ext;
            return this;
        }

        /**
         * Sets blocksize
         * @param blockSize
         * @return builder
         */
        public Builder setBlockSize(int blockSize) {
            this.mBlockSize = blockSize;
            return this;
        }

        public Builder setSig(byte[] sig) {
            this.mSig = sig;
            return this;
        }

        public Builder setMime(String mime) {
            this.mime = mime;
            return this;
        }

        public Builder setEndOfStream() {
            this.endofstream = true;
            return this;
        }

        public Builder setFilename(String filename) {
            this.filename = filename;
            return this;
        }

        public byte[] getSig() {
            return mSig;
        }

        /**
         * Build block header packet.
         *
         * @return the block header packet
         */
        public BlockHeaderPacket build() {
            if (hashlist == null)
                throw new IllegalArgumentException("hashlist was null");

            // fingerprints and application are required
            if (application == null) {
                throw new IllegalArgumentException("application was null");
            }

            if (extension == null) {
                extension = ".data";
            }

            if (mBlockSize <= 0) {
                IllegalArgumentException e = new IllegalArgumentException("blocksize not set");
                e.printStackTrace();
                throw e;
            }

            return new BlockHeaderPacket(this);
        }

        /**
         * Get application name (in UTF-8).
         *
         * @return the byte [ ]
         */
        public byte[] getApplication() {
            return application;
        }

        /**
         * Gets sessionid.
         *
         * @return the sessionid
         */
        public int getSessionid() {
            return sessionid;
        }

        /**
         * Gets hashlist.
         *
         * @return the hashlist
         */
        public List<ByteString> getHashlist() {
            return hashlist;
        }

        /**
         * Gets to fingerprint.
         *
         * @return the to fingerprint
         */
        public byte[] getmToFingerprint() {
            return mToFingerprint;
        }

        /**
         * Gets from fingerprint.
         *
         * @return the from fingerprint
         */
        public byte[] getmFromFingerprint() {
            return mFromFingerprint;
        }

        /**
         * Gets to disk.
         *
         * @return the to disk
         */
        public boolean getToDisk() {
            return todisk;
        }

        /**
         * Gets blocksize
         * @return blocksize
         */
        public int getBlockSize() { return mBlockSize; }
    }
}
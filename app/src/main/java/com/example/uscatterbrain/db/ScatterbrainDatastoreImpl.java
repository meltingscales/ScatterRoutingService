package com.example.uscatterbrain.db;

import android.content.Context;

import com.example.uscatterbrain.RoutingServiceComponent;
import com.example.uscatterbrain.db.entities.Identity;
import com.example.uscatterbrain.db.entities.Keys;
import com.example.uscatterbrain.db.entities.MessageHashCrossRef;
import com.example.uscatterbrain.db.entities.ScatterMessage;
import com.example.uscatterbrain.db.file.FileStore;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;

/**
 * Interface to the androidx room backed datastore
 * used for storing messages, identities, and other metadata.
 */
public class ScatterbrainDatastoreImpl implements ScatterbrainDatastore {

    private final Datastore mDatastore;
    private final Context ctx;
    private final Scheduler databaseScheduler;
    private final FileStore fileStore;
    /**
     * constructor
     * @param ctx  application or service context
     */
    @Inject
    public ScatterbrainDatastoreImpl(
            Context ctx,
            Datastore datastore,
            FileStore fileStore,
            @Named(RoutingServiceComponent.NamedSchedulers.DATABASE) Scheduler databaseScheduler
    ) {
        mDatastore = datastore;
        this.ctx = ctx;
        this.databaseScheduler = databaseScheduler;
        this.fileStore = fileStore;
    }

    /**
     *  For internal use, synchronously inserts messages to database
     * @param message room entity for message to insert
     * @return primary keys of message inserted
     */
    @Override
    public Completable insertMessagesSync(ScatterMessage message) {
        return this.mDatastore.scatterMessageDao().insertIdentity(message.getIdentity())
                .flatMap(identityid -> this.mDatastore.scatterMessageDao().insertHashes(message.getHashes())
                        .flatMap(hashids -> {
                            message.setIdentityID(identityid);
                            return mDatastore.scatterMessageDao()._insertMessages(message)
                                    .flatMap(messageid -> {
                                        List<MessageHashCrossRef> hashes = new ArrayList<>();
                                        for (Long hashID : hashids) {
                                            MessageHashCrossRef xref = new MessageHashCrossRef();
                                            xref.messageID = messageid;
                                            xref.hashID = hashID;
                                            hashes.add(xref);
                                        }
                                        return this.mDatastore.scatterMessageDao().insertMessagesWithHashes(hashes);
                                    });
                        })
                )
                .ignoreElement();
    }

    /**
     * For internal use, synchronously inserts messages into the database
     * @param messages list of room entities to insert
     * @return list of primary keys for rows inserted
     */
    private Completable insertMessagesSync(List<ScatterMessage> messages) {
        return Observable.fromIterable(messages)
                .flatMap(scatterMessage -> insertMessagesSync(scatterMessage).toObservable())
                .ignoreElements();
    }

    /**
     * Asynchronously inserts a list of messages into the datastore, allows tracking result
     * via provided callback
     *
     * @param messages room entities to insert
     * @return future returning list of ids inserted
     */
    @Override
    public Completable insertMessages(List<ScatterMessage> messages) {
        return insertMessagesSync(messages);
    }

    /**
     * Asynchronously inserts a single message into the datastore, allows tracking result
     * via provided callback
     *
     * @param message room entity to insert
     * @return future returning id of row inserted
     */
    @Override
    public Completable insertMessage(ScatterMessage message) {
        return insertMessagesSync(message);
    }

    @Override
    public Completable insertMessage(WifiDirectRadioModule.BlockDataStream stream) {
        return insertMessage(stream.getEntity())
                .andThen(fileStore.insertFile(stream));
    }

    /**
     * Asynchronously inserts a list of identities into the datastore, allows tracking result
     * via provided callback
     *
     * @param identities list of room entities to insert
     * @return future returning list of row ids inserted
     */
    @Override
    public Completable insertIdentity(Identity[] identities) {
        return mDatastore.identityDao().insertAll(identities).ignoreElement();
    }

    /**
     * Asynchronously inserts an identity into the datastore, allows tracking result
     * via provided callback
     *
     * @param identity room entity to insert
     * @return future returning row id inserted
     */
    @Override
    public Completable insertIdentity(Identity identity) {
        return mDatastore.identityDao().insertAll(identity).ignoreElement();
    }

    /**
     * gets a randomized list of messages from the datastore. Needs to be observed
     * to get async result
     *
     * @param count how many messages to retrieve
     * @return livedata representation of list of messages
     */
    @Override
    public Observable<WifiDirectRadioModule.BlockDataStream> getTopRandomMessages(int count) {
        return this.mDatastore.scatterMessageDao().getTopRandom(count)
                .toObservable()
                .flatMap(Observable::fromIterable)
                .map(scatterMessage -> new WifiDirectRadioModule.BlockDataStream(
                        scatterMessage,
                        fileStore.readFile(fileStore.getFilePath(scatterMessage).toPath(), scatterMessage.getBlocksize())
                        ));
    }

    /**
     * gets a list of all the files in the datastore.
     * @return list of DiskFiles objects
     */
    @Override
    public Observable<String> getAllFiles() {
        return this.mDatastore.scatterMessageDao().getAllFiles()
                .toObservable()
                .flatMap(Observable::fromIterable);
    }

    /**
     * Retrieves a message by an identity room entity
     *
     * @param id room entity to search by
     * @return livedata representation of list of messages
     */
    @Override
    public Observable<ScatterMessage> getMessagesByIdentity(Identity id) {
        return this.mDatastore.scatterMessageDao().getByIdentity(id.getIdentityID())
                .toObservable()
                .flatMap(Observable::fromIterable);
    }

    @Override
    public Completable insertIdentity(List<com.example.uscatterbrain.identity.Identity> identity) {
        return Observable.fromCallable(() -> {
            List<Identity> idlist = new ArrayList<>();
            List<Keys> keysList = new ArrayList<>();
            for (com.example.uscatterbrain.identity.Identity identity1 : identity) {
                Identity id = new Identity();
                id.setGivenName(identity1.getName());
                id.setPublicKey(identity1.getPubkey());
                id.setSignature(identity1.getSig());

                idlist.add(id);
            }
            return mDatastore.identityDao().insertAll(idlist)
                    .flatMap(identityidlist -> {
                        if(identityidlist.size() != identity.size()) {
                            return Single.error(new IllegalStateException("identity list sizes do not match"));
                        }

                        for (int i=0;i<identity.size();i++) {
                            com.example.uscatterbrain.identity.Identity identity1 = identity.get(i);
                            for (Map.Entry<String, ByteString> entry : identity1.getKeymap().entrySet()) {
                                Keys k = new Keys();
                                k.setKey(entry.getKey());
                                k.setValue(entry.getValue().toByteArray());
                                k.setIdentityFK(identityidlist.get(i));
                                keysList.add(k);
                            }
                        }
                        return mDatastore.identityDao().insertKeys(keysList);
                    });

        }).ignoreElements();
     }

     /*
     @Override
     public Completable insertDataPacket(BlockDataObservableSource packet) {
         if(!packet.isHashValid().blockingGet()) {
            return Completable.error(new IllegalStateException("inserted a packet with invalid hash"));
         }

         return Completable.fromCallable(() -> {
             ScatterMessage message = new ScatterMessage();
             message.setFilePath(packet.getFile().getAbsolutePath());
             message.setBody(new byte[0]); //TODO: implement body
             message.setFrom(packet.getHeader().blockingGet().getFromFingerprint().toByteArray());
             message.setTo(packet.getHeader().blockingGet().getToFingerprint().toByteArray());
             message.setSig(packet.getHeader().blockingGet().getSig().toByteArray());
             message.setApplication(packet.getHeader().blockingGet().getApplication());
             message.setSessionid(packet.getHeader().blockingGet().getSessionID());
             message.setIdentity(null); //TODO: update identity later
             Log.e("debug", "header blocksize " + packet.getHeader().blockingGet().getBlockSize());
             message.setBlocksize(packet.getHeader().blockingGet().getBlockSize());

             List<Hashes> hashlist = new ArrayList<>();
             for (ByteString hash : packet.getHashes().blockingGet()) {
                 Hashes hashes = new Hashes();
                 hashes.setHash(hash.toByteArray());
                 hashlist.add(hashes);
             }

             message.setHashes(hashlist);
             return insertMessagesSync(message);
         });
     }
*/

     public Observable<com.example.uscatterbrain.identity.Identity> getIdentity(List<Long> ids) {
            return mDatastore.identityDao().getIdentitiesWithRelations(ids)
                    .toObservable()
                        .flatMap(idlist -> {
                            return Observable.fromIterable(idlist)
                                    .map(relation -> {
                                        Map<String, ByteString> keylist = new HashMap<>(relation.keys.size());
                                        for (Keys keys : relation.keys) {
                                            keylist.put(keys.getKey(), ByteString.copyFrom(keys.getValue()));
                                        }
                                        com.example.uscatterbrain.identity.Identity identity = com.example.uscatterbrain.identity.Identity.newBuilder(ctx)
                                                .setName(relation.identity.getGivenName())
                                                .setScatterbrainPubkey(ByteString.copyFrom(relation.identity.getPublicKey()))
                                                .setSig(relation.identity.getSignature())
                                                .build();

                                        identity.putAll(keylist);
                                        return identity;
                                    });
                        });
    }


    /*
     @Override
     public Observable<BlockDataObservableSource> getDataPacket(List<Long> id) {
            return mDatastore.scatterMessageDao().getByID(id)
                    .toObservable()
                    .flatMap(messages -> {
                        return Observable.fromIterable(messages)
                                .flatMap(message -> {
                                    File f = Paths.get(message.getFilePath()).toAbsolutePath().toFile();
                                    BlockDataSourceFactory.BuildOptions options = new BlockDataSourceFactory.BuildOptions.Builder()
                                            .setApplication(ByteString.copyFrom(message.getApplication()).toStringUtf8())
                                            .setFromAddress(ByteString.copyFrom(message.getFrom()))
                                            .setToAddress(ByteString.copyFrom(message.getTo()))
                                            .setSessionID(message.getSessionid())
                                            .setBlockSize(message.getBlocksize())
                                            .setFragmentFile(f)
                                            .setSig(ByteString.copyFrom(message.getSig()))
                                            .build();

                                    return blockDataSourceFactory.buildSource(options).toObservable();

                                });
                    });
     }
*/

    /**
     * Clears the datastore, dropping all tables
     */
    @Override
    public void clear() {
        this.mDatastore.clearAllTables();
    }
}

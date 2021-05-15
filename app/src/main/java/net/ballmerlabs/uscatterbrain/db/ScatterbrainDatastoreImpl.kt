package net.ballmerlabs.uscatterbrain.db

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import android.util.Pair
import android.webkit.MimeTypeMap
import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import io.reactivex.functions.Function
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.RouterPreferences
import net.ballmerlabs.uscatterbrain.RoutingServiceBackend.Applications
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.db.*
import net.ballmerlabs.uscatterbrain.db.entities.*
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import java.io.*
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.collections.ArrayList
import kotlin.math.min

/**
 * Interface to the androidx room backed datastore
 * used for storing messages, identities, and associated files
 */
@Singleton
class ScatterbrainDatastoreImpl @Inject constructor(
        private val ctx: Context,
        private val mDatastore: Datastore,
        @param:Named(RoutingServiceComponent.NamedSchedulers.DATABASE) private val databaseScheduler: Scheduler,
        private val preferences: RouterPreferences
) : ScatterbrainDatastore {
    private val mOpenFiles: ConcurrentHashMap<Path, OpenFile> = ConcurrentHashMap()
    private val userFilesDir: File = File(ctx.filesDir, USER_FILES_PATH)
    private val cacheFilesDir: File = File(ctx.filesDir, CACHE_FILES_PATH)
    private val userDirectoryObserver: FileObserver
    override fun insertMessagesSync(message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage): Completable {
        return Completable.fromAction {
            mDatastore.scatterMessageDao().insertMessage(message)
        }.subscribeOn(databaseScheduler)
    }

    /**
     * For internal use, synchronously inserts messages into the database
     * @param messages list of room entities to insert
     * @return list of primary keys for rows inserted
     */
    private fun insertMessagesSync(messages: List<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage>): Completable {
        return Observable.fromIterable(messages)
                .subscribeOn(databaseScheduler)
                .flatMap<Any> { scatterMessage: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage -> insertMessagesSync(scatterMessage).toObservable() }
                .ignoreElements()
    }

    /**
     * Asynchronously inserts a list of messages into the datastore, allows tracking result
     * via provided callback
     *
     * @param messages room entities to insert
     * @return future returning list of ids inserted
     */
    override fun insertMessages(messages: List<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage>): Completable {
        return insertMessagesSync(messages)
    }

    /**
     * Asynchronously inserts a single message into the datastore, allows tracking result
     * via provided callback
     *
     * @param message room entity to insert
     * @return future returning id of row inserted
     */
    override fun insertMessageToRoom(message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage): Completable {
        return insertMessagesSync(message)
    }

    private fun discardStream(stream: BlockDataStream): Completable {
        //TODO: we read and discard packets here because currently, but eventually
        // it would be a good idea to check the hash first and add support for aborting the transfer
        return stream.sequencePackets
                .map<BlockSequencePacket> { packet: BlockSequencePacket ->
                    if (packet.verifyHash(stream.headerPacket)) {
                        Log.v(TAG, "hash verified")
                        return@map packet
                    } else {
                        Log.e(TAG, "hash invalid")
                        return@map null
                    }
                }.ignoreElements()
    }

    /**
     * either insert a blockdatastream to disk or to database only depending on
     * toDisk flag
     * @param stream blockdatastream
     * @return completable
     */
    override fun insertMessage(stream: BlockDataStream): Completable {
        return if (stream.toDisk) {
            insertMessageWithDisk(stream)
        } else {
            insertMessagesWithoutDisk(stream)
        }
    }

    /**
     * Insert a blockdatastream to both database and disk
     */
    private fun insertMessageWithDisk(stream: BlockDataStream): Completable {
        val filePath = getFilePath(stream.headerPacket)
        Log.e(TAG, "inserting message at filePath $filePath")
        stream.entity!!.message.filePath = filePath.absolutePath
        return mDatastore.scatterMessageDao().messageCountSingle(filePath.absolutePath)
                .subscribeOn(databaseScheduler)
                .flatMapCompletable { count: Int ->
                    if (count > 0) {
                        return@flatMapCompletable discardStream(stream)
                    } else {
                        return@flatMapCompletable insertMessageToRoom(stream.entity)
                                .andThen(insertFile(stream))
                    }
                }.subscribeOn(databaseScheduler)
    }

    /**
     * Insert a blockdatastream to database only
     * this implies a size limitation on body
     */
    private fun insertMessagesWithoutDisk(stream: BlockDataStream): Completable {
        return mDatastore.scatterMessageDao().messageCountSingle(stream.headerPacket.autogenFilename)
                .subscribeOn(databaseScheduler)
                .flatMapCompletable { count: Int? ->
                    if (count!! > 0) {
                        return@flatMapCompletable discardStream(stream)
                    } else {
                        return@flatMapCompletable stream.sequencePackets
                                .flatMap<ByteString> { packet: BlockSequencePacket? ->
                                    if (packet!!.verifyHash(stream.headerPacket)) {
                                        return@flatMap Flowable.just(packet.getmData())
                                    } else {
                                        Log.e(TAG, "invalid hash")
                                        return@flatMap Flowable.error<ByteString>(SecurityException("failed to verify hash"))
                                    }
                                }
                                .reduce { obj: ByteString, other: ByteString? -> obj.concat(other) }
                                .flatMapCompletable { `val`: ByteString ->
                                    stream.entity!!.message.body = `val`.toByteArray()
                                    insertMessageToRoom(stream.entity)
                                }.subscribeOn(databaseScheduler)
                    }
                }
    }

    /**
     * encode a binary blob as blocksequence packets
     * @param body data to encode
     * @param blocksize size of each packet
     * @return Flowable with BlockSequencePackets
     */
    override fun readBody(body: ByteArray, blocksize: Int): Flowable<BlockSequencePacket> {
        return Bytes.from(ByteArrayInputStream(body), blocksize)
                .zipWith(seq, { bytes: ByteArray, seq: Int ->
                    BlockSequencePacket.newBuilder()
                            .setData(ByteString.copyFrom(bytes))
                            .setSequenceNumber(seq)
                            .build()
                })
    }


    /**
     * gets a randomized list of messages from the datastore. Needs to be observed
     * to get async result
     *
     * @param count how many messages to retrieve
     * @return livedata representation of list of messages
     */
    override fun getTopRandomMessages(
            count: Int,
            delareHashes: DeclareHashesPacket
    ): Observable<BlockDataStream> {
        Log.v(TAG, "called getTopRandomMessages")
        val num = min(count, mDatastore.scatterMessageDao().messageCount())
        return mDatastore.scatterMessageDao().getTopRandomExclusingHash(count, delareHashes.hashes)
                .subscribeOn(databaseScheduler)
                .doOnSubscribe { Log.v(TAG, "subscribed to getTopRandoMessages") }
                .toFlowable()
                .flatMap { source: List<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage> -> Flowable.fromIterable(source) }
                .doOnNext { message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage -> Log.v(TAG, "retrieved message: " + message.messageHashes.size) }
                .zipWith(seq, BiFunction<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage, Int, BlockDataStream> b@{ message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage, s: Int ->
                    if (message.message.body == null) {
                        return@b BlockDataStream(
                                message,
                                readFile(File(message.message.filePath), message.message.blocksize),
                                s < num - 1,
                                true
                        )
                    } else {
                        return@b BlockDataStream(
                                message,
                                readBody(message.message.body!!, message.message.blocksize),
                                s < num - 1,
                                false
                        )
                    }
                }).toObservable()
                .defaultIfEmpty(BlockDataStream.endOfStream())
    }

    private val seq: Flowable<Int>
        get() = Flowable.generate(Callable { 0 }, BiFunction { state: Int, emitter: Emitter<Int> ->
            emitter.onNext(state)
            state + 1
        })

    /**
     * gets a list of all the files in the datastore.
     * @return list of DiskFiles objects
     */
    override val allFiles: Observable<String>
        get() = mDatastore.scatterMessageDao().allFiles
                .toObservable()
                .flatMap { source: List<String> -> Observable.fromIterable(source) }

    /**
     * Retrieves a message by an identity room entity
     *
     * @param id room entity to search by
     * @return livedata representation of list of messages
     */
    override fun getMessagesByIdentity(id: KeylessIdentity): Observable<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage> {
        return mDatastore.scatterMessageDao().getByIdentity(id.fingerprint)
                .subscribeOn(databaseScheduler)
                .toObservable()
                .flatMap { source: List<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage?>? -> Observable.fromIterable(source) }
    }

    /**
     * reads a file at a given path and transforms it to a ScatterMessage database entity
     * @param path
     * @return single with scattermessage
     */
    override fun getMessageByPath(path: String): Single<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage> {
        return mDatastore.scatterMessageDao().getByFilePath(path)
                .subscribeOn(databaseScheduler)
                .toObservable()
                .flatMap<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage> { source: List<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage?>? -> Observable.fromIterable(source) }
                .firstOrError()
    }

    private fun insertIdentity(identityObservable: Observable<net.ballmerlabs.uscatterbrain.db.entities.Identity>): Completable {
        return identityObservable
                .flatMapCompletable { singleid: net.ballmerlabs.uscatterbrain.db.entities.Identity ->
                    Single.fromCallable { mDatastore.identityDao().insertIdentity(singleid) }
                            .flatMapCompletable { identityId ->
                                Observable.fromCallable { if (singleid.clientACL != null) singleid.clientACL else ArrayList() }
                                        .flatMap<ClientApp> { source: List<ClientApp?>? -> Observable.fromIterable(source) }
                                        .map { acl: ClientApp ->
                                            acl.identityFK = identityId
                                            acl
                                        }
                                        .reduce(ArrayList(), { list: ArrayList<ClientApp>, acl: ClientApp ->
                                            list.add(acl)
                                            list
                                        })
                                        .flatMapCompletable { a: ArrayList<ClientApp> ->
                                            mDatastore.identityDao().insertClientApps(a)
                                                    .subscribeOn(databaseScheduler)
                                                    .ignoreElement()
                                        }
                            }
                }
    }

    /**
     * delete entities from database by fingerprint
     * @param fingerprint
     * @return completable
     */
    override fun deleteIdentities(vararg fingerprint: String): Completable {
        return Observable.fromIterable(fingerprint.asList())
                .map { f -> JustFingerprint(f) }
                .reduce(ArrayList<JustFingerprint>(), {list, f ->
                    list.add(f)
                    list
                })
                .flatMapCompletable { l ->
                    mDatastore.identityDao().deleteIdentityByFingerprint(l)
                            .subscribeOn(databaseScheduler)
                }
    }

    private fun insertIdentity(vararg ids: net.ballmerlabs.uscatterbrain.db.entities.Identity): Completable {
        return Single.just(ids)
                .flatMapCompletable { identities: Array<out net.ballmerlabs.uscatterbrain.db.entities.Identity> ->
                    insertIdentity(Observable.fromArray(*identities))
                            .subscribeOn(databaseScheduler)

                }
    }

    private fun insertIdentity(ids: List<net.ballmerlabs.uscatterbrain.db.entities.Identity>): Completable {
        return Single.just(ids)
                .flatMapCompletable { identities: List<net.ballmerlabs.uscatterbrain.db.entities.Identity>? ->
                    insertIdentity(Observable.fromIterable(identities))
                            .subscribeOn(databaseScheduler)
                }
    }

    private fun getFingerprint(identity: Identity): String {
        val fingeprint = ByteArray(GenericHash.BYTES)
        LibsodiumInterface.sodium.crypto_generichash(
                fingeprint,
                fingeprint.size,
                identity.getmScatterbrainPubKey(),
                identity.getmScatterbrainPubKey().size.toLong(),
                null,
                0
        )
        return LibsodiumInterface.base64enc(fingeprint)
    }

    /**
     * adds permission ACLs to the database
     * @param identityFingerprint identity
     * @param packagename package name to authorize
     * @param appsig signature of application. NOTE: make sure to get this right
     * @return completable
     */
    override fun addACLs(identityFingerprint: String, packagename: String, appsig: String): Completable {
        return mDatastore.identityDao().getIdentityByFingerprint(identityFingerprint)
                .subscribeOn(databaseScheduler)
                .flatMapCompletable { identity: net.ballmerlabs.uscatterbrain.db.entities.Identity? ->
                    val app = ClientApp(
                            identity!!.identity.identityID!!,
                            packagename,
                            appsig
                    )
                    mDatastore.identityDao().insertClientApp(app)
                            .subscribeOn(databaseScheduler)
                }
    }

    /**
     * removes permission ACLs from database
     * @param identityFingerprint identity
     * @param packageName package name to deauthorize
     * @param appsig signature of application
     */
    override fun deleteACLs(identityFingerprint: String, packageName: String, appsig: String): Completable {
        return mDatastore.identityDao().getIdentityByFingerprint(identityFingerprint)
                .subscribeOn(databaseScheduler)
                .flatMapCompletable { identity: net.ballmerlabs.uscatterbrain.db.entities.Identity? ->
                    val app = ClientApp(
                            identity!!.identity.identityID!!,
                            packageName,
                            appsig
                    )
                    mDatastore.identityDao().deleteClientApps(JustPackageName(app.packageName))
                            .subscribeOn(databaseScheduler)
                }
    }

    /**
     * insert api identity to database
     * @param identity identity to insert
     * @return completable
     */
    override fun insertApiIdentity(identity: ApiIdentity): Completable {
        return Single.just(identity)
                .map { dbidentity: ApiIdentity ->
                    val kid = KeylessIdentity(
                            dbidentity.givenname,
                            dbidentity.getmScatterbrainPubKey(),
                            dbidentity.sig,
                            getFingerprint(dbidentity),
                            dbidentity.privateKey
                    )
                    Identity(
                            kid,
                            keys2keysBytes(identity.getmPubKeymap())
                    )
                }.flatMapCompletable { ids: net.ballmerlabs.uscatterbrain.db.entities.Identity ->
                    this.insertIdentity(ids)
                }
    }

    /**
     * insert multiple identities into database
     * @param identities list of identities
     * @return completable
     */
    override fun insertApiIdentities(identities: List<Identity>): Completable {
        return Observable.fromIterable(identities)
                .map { identity: Identity ->
                    val kid = KeylessIdentity(
                            identity.givenname,
                            identity.getmScatterbrainPubKey(),
                            identity.sig,
                            getFingerprint(identity),
                            null
                    )
                    Identity(
                            kid,
                            keys2keysBytes(identity.getmPubKeymap())
                    )
                }.reduce(ArrayList(), { list: ArrayList<net.ballmerlabs.uscatterbrain.db.entities.Identity>, id: net.ballmerlabs.uscatterbrain.db.entities.Identity ->
                    list.add(id)
                    list
                }).flatMapCompletable { ids: ArrayList<net.ballmerlabs.uscatterbrain.db.entities.Identity> ->
                    this.insertIdentity(ids)
                }
    }

    private fun keys2keysBytes(k: Map<String, ByteArray>): List<Keys> {
        val res: MutableList<Keys> = ArrayList()
        for ((key, value) in k) {
            val keys = Keys(
                    key,
                    value
            )
            res.add(keys)
        }
        return res
    }

    private fun keys2keys(k: Map<String, ByteString>): List<Keys> {
        val res: MutableList<Keys> = ArrayList()
        for ((key, value) in k) {
            val keys = Keys(
                    key,
                    value.toByteArray()
            )
            res.add(keys)
        }
        return res
    }

    private fun keys2map(keys: List<Keys>): Map<String, ByteArray> {
        val res = HashMap<String, ByteArray>()
        for (k in keys) {
            res[k.key] = k.value
        }
        return res
    }

    /**
     * insert identity packets from network to database
     * @param identity list of identity packets to insert
     * @return completable
     */
    override fun insertIdentityPacket(identity: List<IdentityPacket>): Completable {
        return Observable.fromIterable(identity)
                .doOnNext { id -> Log.v(TAG, "inserting identity: ${id.fingerprint}")}
                .flatMap { i: IdentityPacket ->
                    if (i.isEnd || i.isEmpty()) {
                        return@flatMap Observable.never<net.ballmerlabs.uscatterbrain.db.entities.Identity>()
                    }
                    val id = KeylessIdentity(
                            i.name!!,
                            i.pubkey!!,
                            i.getSig(),
                            i.fingerprint,
                            null

                    )
                    val finalIdentity = Identity(
                            id,
                            keys2keys(i.keymap)
                    )
                    if (!i.verifyed25519(i.pubkey)) {
                        Log.e(TAG, "identity " + i.name + " " + i.fingerprint + " failed sig check")
                        return@flatMap Observable.never<net.ballmerlabs.uscatterbrain.db.entities.Identity>()
                    }
                    Observable.just(finalIdentity)
                }
                .reduce(ArrayList(), {
                    list: ArrayList<net.ballmerlabs.uscatterbrain.db.entities.Identity>,
                    id: net.ballmerlabs.uscatterbrain.db.entities.Identity ->
                    list.add(id)
                    list
                })
                .flatMapCompletable { ids: ArrayList<net.ballmerlabs.uscatterbrain.db.entities.Identity> ->
                    this.insertIdentity(ids)
                }
    }

    /**
     * gets identities from database by id
     * @param ids
     * @return observable of IdentityPacket (completes even if none)
     */
    override fun getIdentity(ids: List<Long>): Observable<IdentityPacket> {
        return mDatastore.identityDao().getIdentitiesWithRelations(ids)
                .subscribeOn(databaseScheduler)
                .toObservable()
                .flatMap { idlist: List<net.ballmerlabs.uscatterbrain.db.entities.Identity?>? ->
                    Observable.fromIterable(idlist)
                            .map { relation: net.ballmerlabs.uscatterbrain.db.entities.Identity? ->
                                val keylist: MutableMap<String, ByteString> = HashMap(relation!!.keys.size)
                                for (keys in relation.keys) {
                                    keylist[keys.key] = ByteString.copyFrom(keys.value)
                                }
                                val identity: IdentityPacket = IdentityPacket.newBuilder(ctx)
                                        .setName(relation.identity.givenName)
                                        .setScatterbrainPubkey(ByteString.copyFrom(relation.identity.publicKey))
                                        .setSig(relation.identity.signature)
                                        .build()!!
                                identity.putAll(keylist)
                                identity
                            }
                }
    }

    /**
     * gets random identities from database (in network form)
     * @param count
     * @return flowable of identitity packets
     */
    override fun getTopRandomIdentities(count: Int): Flowable<IdentityPacket> {
        val num = min(count, mDatastore.identityDao().getNumIdentities())
        return mDatastore.identityDao().getTopRandom(num)
                .subscribeOn(databaseScheduler)
                .flatMapObservable<net.ballmerlabs.uscatterbrain.db.entities.Identity> { source: List<net.ballmerlabs.uscatterbrain.db.entities.Identity?>? -> Observable.fromIterable(source) }
                .doOnComplete { Log.v(TAG, "datastore retrieved identities: $num") }
                .doOnNext { Log.v(TAG, "retrieved single identity") }
                .toFlowable(BackpressureStrategy.BUFFER)
                .zipWith(seq, { identity: net.ballmerlabs.uscatterbrain.db.entities.Identity, seq: Int ->
                    IdentityPacket.newBuilder(ctx)
                            .setName(identity.identity.givenName)
                            .setScatterbrainPubkey(ByteString.copyFrom(identity.identity.publicKey))
                            .setSig(identity.identity.signature)
                            .setEnd(seq < num - 1)
                            .build()!!
                })
                .defaultIfEmpty(IdentityPacket.newBuilder(ctx).setEnd().build()!!)
    }

    /**
     * gets a single declarehashes packet based on the messages in the datastore
     */
    override val declareHashesPacket: Single<DeclareHashesPacket>
        get() = mDatastore.scatterMessageDao().getTopHashes(
                preferences.getInt(ctx.getString(R.string.pref_declarehashescap), 512)
        )
                .subscribeOn(databaseScheduler)
                .doOnSuccess { p: List<ByteArray> -> Log.v(TAG, "retrieved declareHashesPacket from datastore: " + p.size) }
                .flatMapObservable { source: List<ByteArray> -> Observable.fromIterable(source) }
                .reduce(ArrayList(), { list: ArrayList<ByteArray>, hash: ByteArray ->
                    list.add(hash)
                    list
                })
                .map map@{ hash: ArrayList<ByteArray> ->
                    if (hash.size == 0) {
                        return@map DeclareHashesPacket.newBuilder().optOut().build()
                    } else {
                        return@map DeclareHashesPacket.newBuilder().setHashesByte(hash).build()
                    }
                }

    /**
     * gets an identity by fingerprint in api form
     * @param fingerprint
     * @return identity
     */
    override fun getApiIdentityByFingerprint(fingerprint: String): ApiIdentity {
        return mDatastore.identityDao().getIdentityByFingerprint(fingerprint)
                .subscribeOn(databaseScheduler)
                .map { identity: net.ballmerlabs.uscatterbrain.db.entities.Identity? ->
                    ApiIdentity.newBuilder()
                            .setName(identity!!.identity.givenName)
                            .addKeys(keys2map(identity.keys))
                            .setSig(identity.identity.signature)
                            .setHasPrivateKey(identity.identity.privatekey != null)
                            .build()
                }.blockingGet()
    }

    /**
     * get keypair for identity, including private key if possible
     * @param identity fingerprint
     * @return single of keypair
     */
    override fun getIdentityKey(identity: String): Single<ApiIdentity.KeyPair> {
        return mDatastore.identityDao().getIdentityByFingerprint(identity)
                .subscribeOn(databaseScheduler)
                .map { id: net.ballmerlabs.uscatterbrain.db.entities.Identity? ->
                    checkNotNull(id!!.identity.privatekey) { "private key not found" }
                    ApiIdentity.KeyPair(id.identity.publicKey, id.identity.privatekey!!)
                }
    }

    /**
     * gets all acls for identity
     * @param identity fingerprint
     * @return single of keypair list
     */
    override fun getACLs(identity: String): Single<MutableList<ACL>> {
        return mDatastore.identityDao().getClientApps(identity)
                .subscribeOn(databaseScheduler)
                .flatMapObservable { source: List<ClientApp> -> Observable.fromIterable(source) }
                .map { clientApp: ClientApp ->
                    ACL(
                            clientApp.packageName,
                            clientApp.packageSignature
                    )
                }
                .reduce(ArrayList(), { list: MutableList<ACL>, acl: ACL ->
                    list.add(acl)
                    list
                })
    }

    /**
     * gets dump of all identities in database. Potentially expensive.
     */
    override val allIdentities: List<Identity>
        get() = mDatastore.identityDao().all
                .subscribeOn(databaseScheduler)
                .flatMapObservable {
                    source: List<net.ballmerlabs.uscatterbrain.db.entities.Identity> ->
                    Observable.fromIterable(source) }
                .map { identity: net.ballmerlabs.uscatterbrain.db.entities.Identity ->
                    ApiIdentity.newBuilder()
                            .setName(identity.identity.givenName)
                            .addKeys(keys2map(identity.keys))
                            .setSig(identity.identity.signature)
                            .setHasPrivateKey(identity.identity.privatekey != null)
                            .build()
                }.reduce(ArrayList(), { list: ArrayList<Identity>, id: ApiIdentity ->
                    list.add(id)
                    list
                }).blockingGet()

    /**
     * gets file metadata for use in DocumentsProvider
     * @param path file path
     * @return DocumentsProvider metadata
     */
    override fun getFileMetadataSync(path: File): Map<String, Serializable> {
        return getMessageByPath(path.absolutePath)
                .map { message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage ->
                    val result = HashMap<String, Serializable>()
                    result[DocumentsContract.Document.COLUMN_DOCUMENT_ID] = message.message.filePath
                    result[DocumentsContract.Document.COLUMN_MIME_TYPE] = message.message.mimeType!!
                    if (message.message.userFilename != null) {
                        result[DocumentsContract.Document.COLUMN_DISPLAY_NAME] = message.message.userFilename!!
                    } else {
                        result[DocumentsContract.Document.COLUMN_DISPLAY_NAME] =
                                getDefaultFileNameFromHashes(message.messageHashes)
                    }
                    result[DocumentsContract.Document.COLUMN_FLAGS] = DocumentsContract.Document.FLAG_SUPPORTS_DELETE //TODO: is this enough?
                    result[DocumentsContract.Document.COLUMN_SIZE] = getFileSize(path)
                    result[DocumentsContract.Document.COLUMN_SUMMARY] = "shared via scatterbrain"
                    result
                }
                .onErrorReturn { err: Throwable? -> HashMap() }
                .blockingGet()
    }

    /**
     * inserts a local file into the database by calculating hashes based on blocksize
     * @param path filepath
     * @param blocksize size of packets
     * @return DocumentsProvider metadata
     */
    @Synchronized
    override fun insertAndHashLocalFile(path: File, blocksize: Int): Map<String, Serializable> {
        return hashFile(path, blocksize)
                .flatMapCompletable { hashes: List<ByteString> ->
                    Log.e(TAG, "hashing local file, len:" + hashes.size)
                    val message = HashlessScatterMessage(
                            null,
                            null,
                            null,
                            null,
                            ByteString.copyFromUtf8(Applications.APPLICATION_FILESHARING).toByteArray(),
                            null,
                            0,
                            blocksize,
                            MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(path).toString()),
                            path.absolutePath,
                            getGlobalHash(hashes),
                            path.name,
                            ScatterbrainApi.getMimeType(path)
                    )
                    val hashedMessage = ScatterMessage(
                            message,
                            HashlessScatterMessage.hash2hashs(hashes)
                    )
                    insertMessageToRoom(hashedMessage)
                }.toSingleDefault(getFileMetadataSync(path))
                .blockingGet()
    }

    private fun message2message(message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage): ScatterMessage {
        val f = File(message.message.filePath)
        val r: File = if (f.exists()) {
            f
        } else {
            throw java.lang.IllegalStateException("file doesn't exist")
        }
        return ScatterMessage.newBuilder()
                .setApplication(String(message.message.application))
                .setBody(message.message.body)
                .setFile(r, ParcelFileDescriptor.MODE_READ_ONLY)
                .setTo(message.message.to)
                .setFrom(message.message.from)
                .build()
    }

    private fun getApiMessage(entities: Observable<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage>): Single<MutableList<ScatterMessage>> {
        return entities
                .map { message -> message2message(message) }
                .reduce(ArrayList(), { list , m ->
                    list.add(m)
                    list
                })
    }

    private fun getApiMessage(entity: Single<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage>): Single<ScatterMessage> {
        return entity.map { message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage -> message2message(message) }
    }

    /**
     * gets messages by application in api form
     * @param application
     * @return list of api messages
     */
    override fun getApiMessages(application: String): List<ScatterMessage> {
        return getApiMessage(mDatastore.scatterMessageDao()
                .getByApplication(application)
                .subscribeOn(databaseScheduler)
                .flatMapObservable {
                    source -> Observable.fromIterable(source)
                }
        )
                .blockingGet()
    }

    /**
     * gets messages in api form by database id
     * @param id database id
     * @return api message
     */
    override fun getApiMessages(id: Long): ScatterMessage {
        return getApiMessage(mDatastore.scatterMessageDao().getByID(id))
                .subscribeOn(databaseScheduler)
                .blockingGet()
    }

    /**
     * inserts a file and calculates hashes from api data blob or ParcelFileDescriptor
     * @param message api message
     * @param blocksize blocksize
     * @return completable
     */
    override fun insertAndHashFileFromApi(message: ApiScatterMessage, blocksize: Int): Completable {
        return Single.fromCallable { File.createTempFile("scatterbrain", "insert") }
                .flatMapCompletable { file: File ->
                    if (message.toDisk()) {
                        return@flatMapCompletable copyFile(message.fileDescriptor.fileDescriptor, file)
                                .andThen(hashFile(file, blocksize))
                                .flatMapCompletable { hashes: List<ByteString> ->
                                    val newFile = File(cacheDir, getDefaultFileName(hashes)
                                            + sanitizeFilename(message.extension))
                                    Log.v(TAG, "filepath from api: " + newFile.absolutePath)
                                    if (!file.renameTo(newFile)) {
                                        return@flatMapCompletable Completable.error(IllegalStateException("failed to rename to $newFile"))
                                    }

                                    if (message.signable()) {
                                        message.signEd25519(hashes)
                                    }
                                    val hm = HashlessScatterMessage(
                                            null,
                                            message.identityFingerprint,
                                            message.toFingerprint,
                                            message.fromFingerprint,
                                            ByteString.copyFromUtf8(message.application).toByteArray(),
                                            message.sig,
                                            0,
                                            blocksize,
                                            sanitizeFilename(message.extension),
                                            newFile.absolutePath,
                                            getGlobalHash(hashes),
                                            sanitizeFilename(message.filename),
                                            MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(newFile).toString())

                                    )
                                    val dbmessage = ScatterMessage(
                                            hm,
                                            HashlessScatterMessage.hash2hashs(hashes)
                                    )
                                    dbmessage.message = hm
                                    insertMessageToRoom(dbmessage)
                                }.subscribeOn(databaseScheduler)
                    } else {
                        return@flatMapCompletable hashData(message.body, blocksize)
                                .flatMapCompletable { hashes: List<ByteString> ->

                                    if (message.signable()) {
                                        message.signEd25519(hashes)
                                    }
                                    val hm = HashlessScatterMessage(
                                            message.body,
                                            message.identityFingerprint,
                                            message.toFingerprint,
                                            message.fromFingerprint,
                                            ByteString.copyFromUtf8(message.application).toByteArray(),
                                            message.sig,
                                            0,
                                            blocksize,
                                            "",
                                            getNoFilename(message.body),
                                            getGlobalHash(hashes),
                                            null,
                                            "application/octet-stream"
                                            )

                                    val dbmessage = ScatterMessage(
                                            hm,
                                            HashlessScatterMessage.hash2hashs(hashes)
                                    )
                                    insertMessageToRoom(dbmessage)
                                }
                    }
                }
    }

    /**
     * deletes a message by file path synchronously
     * @param path
     * @return id of message deleted
     */
    @Synchronized
    override fun deleteByPath(path: File): Int {
        return mDatastore.scatterMessageDao()
                .deleteByPath(path.absolutePath)
    }

    /**
     * gets total message count
     * @return count
     */
    override fun messageCount(): Int {
        return mDatastore.scatterMessageDao().messageCount()
    }

    /**
     * Clears the datastore, dropping all tables
     * NOTE: this should probably never be called
     */
    override fun clear() {
        mDatastore.clearAllTables()
    }

    /**
     * wrapper to safe delete a file
     * @param path
     * @return completable
     */
    override fun deleteFile(path: File): Completable {
        return Completable.fromAction {
            if (!path.exists()) {
                throw FileNotFoundException()
            }
            if (!close(path)) {
                throw IllegalStateException("failed to close file")
            }
            if (!path.delete()) {
                throw IllegalStateException("failed to delete file")
            }
        }
    }

    /**
     * checks if a file is cached open
     * @param path
     * @return true if file is open
     */
    override fun isOpen(path: File): Boolean {
        return mOpenFiles.containsKey(path.toPath())
    }

    /**
     * close a file using cache
     * @param path
     * @return true of success, false if failure
     */
    override fun close(path: File): Boolean {
        if (isOpen(path)) {
            val f = mOpenFiles[path.toPath()]
            if (f != null) {
                try {
                    f.close()
                } catch (e: IOException) {
                    return false
                }
                mOpenFiles.remove(path.toPath())
            }
        }
        return true
    }

    override val cacheDir: File
        get() {
            if (!cacheFilesDir.exists()) {
                if (!cacheFilesDir.mkdirs()) {
                    throw java.lang.IllegalStateException("failed to create directory $cacheFilesDir")
                }
            }
            return cacheFilesDir
        }

    override val userDir: File
        get() {
            if (!userFilesDir.exists()) {
                if (!userFilesDir.mkdirs()) {
                    throw java.lang.IllegalStateException("failed to create directory $cacheFilesDir")
                }
            }
            return userFilesDir
        }

    override fun getFilePath(packet: BlockHeaderPacket): File {
        return File(cacheDir, packet.autogenFilename)
    }

    override fun getFileSize(path: File): Long {
        return path.length()
    }

    override fun open(path: File): Single<OpenFile> {
        return Single.fromCallable {
            val old = mOpenFiles[path.toPath()]
            if (old == null) {
                val f = OpenFile(path, false)
                mOpenFiles[path.toPath()] = f
                return@fromCallable f
            } else {
                return@fromCallable old
            }
        }
    }

    private fun insertSequence(packets: Flowable<BlockSequencePacket>, header: BlockHeaderPacket, path: File): Completable {
        return Single.fromCallable { FileOutputStream(path) }
                .flatMapCompletable { fileOutputStream: FileOutputStream? ->
                    packets
                            .concatMapCompletable(Function<BlockSequencePacket, CompletableSource> c@{ blockSequencePacket: BlockSequencePacket? ->
                                if (!blockSequencePacket!!.verifyHash(header)) {
                                    return@c Completable.error(IllegalStateException("failed to verify hash"))
                                }
                                Completable.fromAction { blockSequencePacket.getmData()!!.writeTo(fileOutputStream) }
                                        .subscribeOn(databaseScheduler)
                            })
                }
    }

    /**
     * insert blockdatastream with isFile to datastore
     */
    override fun insertFile(stream: BlockDataStream): Completable {
        val file = getFilePath(stream.headerPacket)
        Log.v(TAG, "insertFile: $file")
        return Completable.fromAction {
            if (!file.createNewFile()) {
                throw FileAlreadyExistsException("file $file already exists")
            }
        }.andThen(insertSequence(
                stream.sequencePackets,
                stream.headerPacket,
                file
        ))
    }

    private fun copyFile(old: FileDescriptor, file: File): Completable {
        return Single.just(Pair(old, file))
                .flatMapCompletable { pair: Pair<FileDescriptor, File> ->
                    if (!pair.second.createNewFile()) {
                        Log.w(TAG, "copyFile overwriting existing file")
                    }
                    if (!pair.first.valid()) {
                        return@flatMapCompletable Completable.error(IllegalStateException("invalid file descriptor: " + pair.first))
                    }
                    val `is` = FileInputStream(pair.first)
                    val os = FileOutputStream(pair.second)
                    Bytes.from(`is`)
                            .flatMapCompletable { bytes: ByteArray? ->
                                Completable.fromAction { os.write(bytes) }
                                        .subscribeOn(databaseScheduler)
                            }
                            .subscribeOn(databaseScheduler)
                            .doFinally {
                                `is`.close()
                                os.close()
                            }
                }
    }

    private fun hashData(data: ByteArray, blocksize: Int): Single<MutableList<ByteString>> {
        return Bytes.from(ByteArrayInputStream(data), blocksize)
                .zipWith(seq, { b: ByteArray, seq: Int ->
                    BlockSequencePacket.newBuilder()
                            .setSequenceNumber(seq)
                            .setData(ByteString.copyFrom(b))
                            .build().getmData()!!
                }).reduce(ArrayList(), { list: MutableList<ByteString>, b: ByteString ->
                    list.add(b)
                    list
                })
    }

    override fun hashFile(path: File, blocksize: Int): Single<List<ByteString>> {
        return Single.fromCallable<List<ByteString>> {
            val r: MutableList<ByteString> = ArrayList()
            if (!path.exists()) {
                throw FileAlreadyExistsException("file already exists")
            }
            val `is` = FileInputStream(path)
            val buf = ByteArray(blocksize)
            var read: Int
            var seqnum = 0
            while (`is`.read(buf).also { read = it } != -1) {
                val blockSequencePacket: BlockSequencePacket = BlockSequencePacket.newBuilder()
                        .setSequenceNumber(seqnum)
                        .setData(ByteString.copyFrom(buf, 0, read))
                        .build()
                r.add(blockSequencePacket.calculateHashByteString())
                seqnum++
                Log.e("debug", "hashing $read")
            }
            r
        }.subscribeOn(databaseScheduler)
    }

    override fun readFile(path: File, blocksize: Int): Flowable<BlockSequencePacket> {
        Log.v(TAG, "called readFile $path")
        return if (!path.exists()) {
            Flowable.error(FileNotFoundException(path.toString()))
        } else Flowable.fromCallable { FileInputStream(path) }
                .doOnSubscribe { Log.v(TAG, "subscribed to readFile") }
                .flatMap {
                    Bytes.from(path, blocksize)
                            .zipWith(seq, { bytes: ByteArray, seqnum: Int ->
                                Log.e("debug", "reading " + bytes.size)
                                BlockSequencePacket.newBuilder()
                                        .setSequenceNumber(seqnum)
                                        .setData(ByteString.copyFrom(bytes))
                                        .build()
                            }).subscribeOn(databaseScheduler)
                }.doOnComplete { Log.v(TAG, "readfile completed") }
    }

    companion object {
        private const val TAG = "ScatterbrainDatastore"
    }

    init {
        userDir //create user and cahce directories so we can monitor them
        cacheDir
        userDirectoryObserver = object : FileObserver(userFilesDir) {
            override fun onEvent(i: Int, s: String?) {
                when (i) {
                    CLOSE_WRITE -> {
                        if (s != null) {
                            Log.v(TAG, "file closed in user directory; $s")
                            val f = File(userFilesDir, s)
                            if (f.exists() && f.length() > 0) {
                                insertAndHashLocalFile(f, DEFAULT_BLOCKSIZE)
                            } else if (f.length() == 0L) {
                                Log.e(TAG, "file length was zero, not hashing")
                            } else {
                                Log.e(TAG, "closed file does not exist, race condition??!")
                            }
                        }
                    }
                    OPEN -> {
                        if (s != null) {
                            Log.v(TAG, "file created in user directory: $s")
                        }
                    }
                    DELETE -> {
                        if (s != null) {
                            Log.v(TAG, "file deleted in user directory: $s")
                        }
                    }
                }
            }
        }
        userDirectoryObserver.startWatching()
    }
}
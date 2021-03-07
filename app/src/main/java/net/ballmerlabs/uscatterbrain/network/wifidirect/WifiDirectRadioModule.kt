package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.net.wifi.p2p.WifiP2pInfo
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket
import net.ballmerlabs.uscatterbrain.network.BlockSequencePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest

interface WifiDirectRadioModule {
    fun connectToGroup(name: String, passphrase: String, timeout: Int): Single<WifiP2pInfo>
    fun bootstrapFromUpgrade(upgradeRequest: BootstrapRequest): Single<HandshakeResult>
    fun unregisterReceiver()
    fun registerReceiver()
    fun createGroup(name: String, passphrase: String): Completable
    class BlockDataStream {
        val sequencePackets: Flowable<BlockSequencePacket>
        val headerPacket: BlockHeaderPacket
        val entity: ScatterMessage
        private val sequenceCompletable = CompletableSubject.create()

        constructor(headerPacket: BlockHeaderPacket, sequencePackets: Flowable<BlockSequencePacket>) {
            this.sequencePackets = sequencePackets
                    .doOnComplete { sequenceCompletable.onComplete() }
                    .doOnError { e: Throwable? -> sequenceCompletable.onError(e!!) }
            this.headerPacket = headerPacket
            entity = ScatterMessage(
                    HashlessScatterMessage(
                            null,
                            null,
                            headerPacket.toFingerprint.toByteArray(),
                            headerPacket.fromFingerprint.toByteArray(),
                            headerPacket.application,
                            headerPacket.signature,
                            headerPacket.sessionID,
                            headerPacket.blockSize,
                            headerPacket.getExtension(),
                            ScatterbrainDatastore.getDefaultFileName(headerPacket),
                            ScatterbrainDatastore.getGlobalHash(headerPacket.hashList),
                            headerPacket.userFilename,
                            headerPacket.mime
                    ),
                    HashlessScatterMessage.hash2hashs(headerPacket.hashList)
            )
        }

        fun await(): Completable {
            return sequencePackets.ignoreElements()
        }

        val toDisk: Boolean
            get() = headerPacket.toDisk

        @JvmOverloads
        constructor(message: ScatterMessage, packetFlowable: Flowable<BlockSequencePacket>, end: Boolean = false, todisk: Boolean = true) {
            val builder: BlockHeaderPacket.Builder = BlockHeaderPacket.newBuilder()
                    .setToFingerprint(message.message.to)
                    .setFromFingerprint(message.message.from)
                    .setApplication(message.message.application)
                    .setSig(message.message.sig)
                    .setToDisk(todisk)
                    .setSessionID(message.message.sessionid)
                    .setBlockSize(message.message.blocksize)
                    .setMime(message.message.mimeType)
                    .setExtension(message.message.extension)
                    .setHashes(HashlessScatterMessage.hashes2hash(message.messageHashes))
            if (end) {
                builder.setEndOfStream()
            }
            headerPacket = builder.build()
            entity = message
            sequencePackets = packetFlowable
                    .doOnComplete { sequenceCompletable.onComplete() }
                    .doOnError { e: Throwable? -> sequenceCompletable.onError(e!!) }
        }

        fun awaitSequencePackets(): Completable {
            return sequenceCompletable
        }

    }

    companion object {
        const val TAG = "WifiDirectRadioModule"
    }
}
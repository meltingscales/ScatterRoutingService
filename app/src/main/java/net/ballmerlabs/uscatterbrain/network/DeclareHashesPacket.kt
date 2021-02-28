package net.ballmerlabs.uscatterbrain.network

import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterProto.DeclareHashes
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable.PacketType
import java.io.*
import java.util.*
import kotlin.collections.ArrayList

class DeclareHashesPacket private constructor(builder: Builder) : ScatterSerializable {
    private val declareHashes: DeclareHashes?
    override var luid: UUID? = null
        private set

    init {
        declareHashes = DeclareHashes.newBuilder()
                .setOptout(builder.optout)
                .addAllHashes(builder.hashes)
                .build()
    }

    override val bytes: ByteArray
        get() {
            val os = ByteArrayOutputStream()
            return try {
                CRCProtobuf.writeToCRC(declareHashes, os)
                os.toByteArray()
            } catch (ignored: IOException) {
                byteArrayOf(0) //this should be unreachable
            }
        }

    override val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    override fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { CRCProtobuf.writeToCRC(declareHashes, os) }
    }

    override fun writeToStream(fragsize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
    }

    override val type: PacketType
        get() = PacketType.TYPE_DECLARE_HASHES

    override fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    val hashes: List<ByteArray>
        get() {
            val r = ArrayList<ByteArray>()
            for (b in declareHashes!!.hashesList) {
                r.add(b.toByteArray())
            }
            return r
        }

    class Builder {
        var hashes: ArrayList<ByteString> = ArrayList()
        var optout = false
        fun setHashes(hashes: List<ByteString>): Builder {
            this.hashes.addAll(hashes)
            return this
        }

        fun setHashesByte(hashes: List<ByteArray>): Builder {
            this.hashes = ArrayList()
            for (bytes in hashes) {
                this.hashes.add(ByteString.copyFrom(bytes))
            }
            return this
        }

        fun optOut(): Builder {
            optout = true
            return this
        }

        fun build(): DeclareHashesPacket {
            if (hashes == null) {
                hashes = ArrayList()
            }
            return DeclareHashesPacket(this)
        }

    }

    companion object {

        private fun builderFromIs(inputStream: InputStream) : Builder {
            val declareHashes = CRCProtobuf.parseFromCRC(DeclareHashes.parser(), inputStream)
            val builder = Builder()
            if (declareHashes.optout) {
                builder.optout = true;
            } else {
                builder.setHashes(declareHashes.hashesList)
            }
            return builder
        }

        fun newBuilder(): Builder {
            return Builder()
        }

        fun parseFrom(inputStream: InputStream): Single<DeclareHashesPacket> {
            return Single.fromCallable { DeclareHashesPacket(builderFromIs(inputStream)) }
        }

        fun parseFrom(flowable: Observable<ByteArray>): Single<DeclareHashesPacket> {
            val observer = InputStreamObserver(4096) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        fun parseFrom(flowable: Flowable<ByteArray>): Single<DeclareHashesPacket> {
            val observer = InputStreamFlowableSubscriber(4096) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }
    }
}
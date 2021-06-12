package net.ballmerlabs.uscatterbrain.network

import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import net.ballmerlabs.uscatterbrain.ScatterProto.BlockSequence
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wrapper class for protocol buffer BlockSequence message
 */
class BlockSequencePacket(packet: BlockSequence) : ScatterSerializable<BlockSequence>(packet) {

    val data: ByteArray
        get() = if (isNative) {
        ByteArray(0)
    } else {
        packet.dataContents.toByteArray()
    }

    val sequenceNum: Int
    get() = packet.seqnum

    val isNative: Boolean
    get() = packet.dataCase == BlockSequence.DataCase.DATA_NATIVE

    /**
     * Verify the hash of this message against its header
     *
     * @param bd the bd
     * @return boolean whether verification succeeded
     */
    fun verifyHash(bd: BlockHeaderPacket?): Boolean {
        val seqnum = ByteBuffer.allocate(4).putInt(sequenceNum).order(ByteOrder.BIG_ENDIAN).array()
        val testhash = ByteArray(GenericHash.BYTES)
        val state = ByteArray(LibsodiumInterface.sodium.crypto_generichash_statebytes())
        LibsodiumInterface.sodium.crypto_generichash_init(state, null, 0, testhash.size)
        LibsodiumInterface.sodium.crypto_generichash_update(state, seqnum, seqnum.size.toLong())
        LibsodiumInterface.sodium.crypto_generichash_update(state, data, data.size.toLong())
        LibsodiumInterface.sodium.crypto_generichash_final(state, testhash, testhash.size)
        return LibsodiumInterface.sodium.sodium_compare(testhash, bd!!.getHash(sequenceNum).toByteArray(), testhash.size) == 0
    }

    /**
     * Calculates the hash of this message
     *
     * @return the hash
     */
    fun calculateHash(): ByteArray {
        val hashbytes = ByteArray(GenericHash.BYTES)
        val state = ByteArray(LibsodiumInterface.sodium.crypto_generichash_statebytes())
        val seqnum = ByteBuffer.allocate(4).putInt(sequenceNum).order(ByteOrder.BIG_ENDIAN).array()
        LibsodiumInterface.sodium.crypto_generichash_init(state, null, 0, hashbytes.size)
        LibsodiumInterface.sodium.crypto_generichash_update(state, seqnum, seqnum.size.toLong())
        LibsodiumInterface.sodium.crypto_generichash_update(state, data, data.size.toLong())
        LibsodiumInterface.sodium.crypto_generichash_final(state, hashbytes, hashbytes.size)
        return hashbytes
    }

    /**
     * Calculates the hash of this message
     *
     * @return hash as ByteString
     */
    fun calculateHashByteString(): ByteString {
        return ByteString.copyFrom(calculateHash())
    }

    override val type: PacketType
        get() = PacketType.TYPE_BLOCKSEQUENCE

    /**
     * Builder class for BlockSequencePacket
     */
    data class Builder(
            var sequenceNumber: Int = 0,
            var data: ByteString? = null,
            val dataOnDisk: File? = null,
            var onDisk: Boolean = false
    )
    /**
     * Instantiates a new Builder.
     */
    {

        /**
         * Sets sequence number.
         *
         * @param sequenceNumber the sequence number
         * @return the sequence number
         */
        fun setSequenceNumber(sequenceNumber: Int) = apply {
            this.sequenceNumber = sequenceNumber
        }

        /**
         * Sets data.
         *
         * @param data the data
         * @return the data
         */
        fun setData(data: ByteString?) = apply {
            this.data = data
        }

        /**
         * Build block sequence packet.
         *
         * @return the block sequence packet
         */
        fun build(): BlockSequencePacket {
            val builder = BlockSequence.newBuilder()
            if (data != null) {
                builder.dataContents = data
            } else {
                builder.dataNative = true
            }
            return BlockSequencePacket(builder.setSeqnum(sequenceNumber).build())
        }
    }

    companion object {
        /**
         * New builder builder.
         *
         * @return the builder
         */
        fun newBuilder(): Builder {
            return Builder()
        }

        class Parser : ScatterSerializable.Companion.Parser<BlockSequence, BlockSequencePacket>(BlockSequence.parser())
        fun parser(): Parser {
            return Parser()
        }
    }
}
package com.mrpowergamerbr.butterscotchpreprocessor

class BinaryReader(private val data: ByteArray) {
    var position: Int = 0

    val size: Int get() = data.size

    fun readUint8(): Int = data[position++].toInt() and 0xFF

    fun readInt16(): Short {
        val lo = data[position++].toInt() and 0xFF
        val hi = data[position++].toInt() and 0xFF
        return ((hi shl 8) or lo).toShort()
    }

    fun readUint16(): Int = readInt16().toInt() and 0xFFFF

    fun readInt32(): Int {
        val b0 = data[position++].toInt() and 0xFF
        val b1 = data[position++].toInt() and 0xFF
        val b2 = data[position++].toInt() and 0xFF
        val b3 = data[position++].toInt() and 0xFF
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    fun readFloat32(): Float = Float.fromBits(readInt32())

    fun readLong(): Long {
        val lo = readInt32().toLong() and 0xFFFFFFFFL
        val hi = readInt32().toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }

    fun readBool32(): Boolean = readInt32() != 0

    fun readBytes(count: Int): ByteArray {
        val bytes = data.copyOfRange(position, position + count)
        position += count
        return bytes
    }

    fun readBytesAt(offset: Int, count: Int): ByteArray {
        val saved = position
        position = offset
        val bytes = readBytes(count)
        position = saved
        return bytes
    }

    fun skip(count: Int) {
        position += count
    }

    // Reads a uint32 absolute file offset to a string.
    // The offset points to the string content; the 4-byte length prefix is at offset - 4.
    fun readStringPtr(): String? {
        val offset = readInt32()
        if (offset == 0) return null
        val saved = position
        position = offset - 4
        val length = readInt32()
        val bytes = readBytes(length)
        position = saved
        return bytes.decodeToString()
    }

    // Reads a pointer table: uint32 count + count * uint32 absolute offsets
    fun readPointerTable(): IntArray {
        val count = readInt32()
        if (count == 0) return intArrayOf()
        return IntArray(count) { readInt32() }
    }

    fun readChunkName(): String {
        val bytes = readBytes(4)
        return bytes.map { it.toInt().toChar() }.toCharArray().concatToString()
    }
}

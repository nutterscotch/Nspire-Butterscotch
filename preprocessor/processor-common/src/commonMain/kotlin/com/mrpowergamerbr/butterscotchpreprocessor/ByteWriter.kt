package com.mrpowergamerbr.butterscotchpreprocessor

class ByteWriter(initialCapacity: Int = 256) {
    init {
        require(initialCapacity > 0) { "Initial capacity must be greater than 0!" }
    }

    private var buffer: ByteArray = ByteArray(initialCapacity)

    var size = 0
        private set

    var position: Int
        get() = size
        set(value) {
            // Allow seeking within the written area (for header-then-seek-to-end pattern)
            require(size >= value) { "Cannot seek past end of written data" }
            size = value
        }

    fun writeByte(value: Int) {
        resizeBufferIfNeeded(size + 1)
        buffer[size++] = value.toByte()
    }

    fun writeShortLE(value: Int) {
        resizeBufferIfNeeded(size + 2)
        buffer[size++] = (value and 0xFF).toByte()
        buffer[size++] = ((value shr 8) and 0xFF).toByte()
    }

    fun writeIntLE(value: Int) {
        resizeBufferIfNeeded(size + 4)
        buffer[size++] = (value and 0xFF).toByte()
        buffer[size++] = ((value shr 8) and 0xFF).toByte()
        buffer[size++] = ((value shr 16) and 0xFF).toByte()
        buffer[size++] = ((value shr 24) and 0xFF).toByte()
    }

    fun writeShortBE(value: Int) {
        resizeBufferIfNeeded(size + 2)
        buffer[size++] = ((value shr 8) and 0xFF).toByte()
        buffer[size++] = (value and 0xFF).toByte()
    }

    fun writeIntBE(value: Int) {
        resizeBufferIfNeeded(size + 4)
        buffer[size++] = ((value shr 24) and 0xFF).toByte()
        buffer[size++] = ((value shr 16) and 0xFF).toByte()
        buffer[size++] = ((value shr 8) and 0xFF).toByte()
        buffer[size++] = (value and 0xFF).toByte()
    }

    fun writeByteArray(array: ByteArray) {
        resizeBufferIfNeeded(size + array.size)
        array.copyInto(buffer, size)
        size += array.size
    }

    fun writeZeroPadding(count: Int) {
        resizeBufferIfNeeded(size + count)
        // ByteArray is already zero-initialized, but we still need to advance the position
        for (i in 0 until count) {
            buffer[size++] = 0
        }
    }

    private fun resizeBufferIfNeeded(requiredCapacity: Int) {
        if (requiredCapacity > buffer.size) {
            val newSize = (requiredCapacity * 1.5).toInt().coerceAtLeast(requiredCapacity + 1)
            buffer = buffer.copyOf(newSize)
        }
    }

    fun getAsByteArray(): ByteArray {
        val result = ByteArray(size)
        buffer.copyInto(result, 0, 0, size)
        return result
    }
}

package com.mrpowergamerbr.butterscotchpreprocessor

// Minimal pkzip writer (STORE method only, no compression). Used to bundle the
// Nspire cook's five .tns files into a single download — browsers can't write
// directories, and the cooked-nspire layout the calculator expects is a directory.
// STORE is the right method here: the .tns files are already binary/RLE-compressed
// atlases or game bytecode, deflate would barely help and would be slow in-browser.
//
// Format reference: PKWARE APPNOTE — Local File Header, Central Directory File
// Header, End Of Central Directory Record. No Zip64 (file sizes well under 4 GB).
// CRC-32 is the standard IEEE polynomial (0xEDB88320 reflected).

private val CRC32_TABLE = IntArray(256).also { t ->
    for (i in 0 until 256) {
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        t[i] = c
    }
}

private fun crc32(data: ByteArray): Int {
    var c = -1
    for (b in data) c = (c ushr 8) xor CRC32_TABLE[(c xor b.toInt()) and 0xFF]
    return c.inv()
}

class StoreZipBuilder {
    data class Entry(val name: String, val data: ByteArray)

    private val entries = mutableListOf<Entry>()

    fun add(name: String, data: ByteArray) {
        entries.add(Entry(name, data))
    }

    fun build(): ByteArray {
        // Two-pass: emit local headers + data, recording offsets and CRCs for the
        // central directory; then emit central directory + EOCD.
        data class Meta(val crc: Int, val offset: Int, val nameBytes: ByteArray)
        val out = ByteBuf()
        val metas = ArrayList<Meta>(entries.size)

        for (e in entries) {
            val nameBytes = e.name.encodeToByteArray()
            val crc = crc32(e.data)
            val offset = out.size
            // Local File Header: 0x04034b50, version 20, flags 0x0800 (UTF-8 names),
            // method 0 (store), mtime/mdate 0, crc32, csize, usize, name_len, extra_len 0.
            out.u32(0x04034b50)
            out.u16(20)
            out.u16(0x0800)
            out.u16(0)
            out.u16(0); out.u16(0)
            out.u32(crc)
            out.u32(e.data.size)
            out.u32(e.data.size)
            out.u16(nameBytes.size)
            out.u16(0)
            out.bytes(nameBytes)
            out.bytes(e.data)
            metas.add(Meta(crc, offset, nameBytes))
        }

        val cdOffset = out.size
        for ((i, e) in entries.withIndex()) {
            val m = metas[i]
            // Central Directory File Header: 0x02014b50.
            out.u32(0x02014b50)
            out.u16(20)             // version made by
            out.u16(20)             // version needed
            out.u16(0x0800)         // flags
            out.u16(0)              // method (store)
            out.u16(0); out.u16(0)  // mtime/mdate
            out.u32(m.crc)
            out.u32(e.data.size)    // compressed size
            out.u32(e.data.size)    // uncompressed size
            out.u16(m.nameBytes.size)
            out.u16(0)              // extra
            out.u16(0)              // comment
            out.u16(0)              // disk number start
            out.u16(0)              // internal attrs
            out.u32(0)              // external attrs
            out.u32(m.offset)       // local header offset
            out.bytes(m.nameBytes)
        }
        val cdSize = out.size - cdOffset

        // End Of Central Directory Record: 0x06054b50.
        out.u32(0x06054b50)
        out.u16(0)                  // this disk
        out.u16(0)                  // disk with cd start
        out.u16(entries.size)       // records on this disk
        out.u16(entries.size)       // total records
        out.u32(cdSize)
        out.u32(cdOffset)
        out.u16(0)                  // comment length

        return out.toByteArray()
    }
}

// Tiny little-endian byte buffer with dynamic growth. Avoids pulling in any
// platform-specific stream type so this works in jsMain unchanged.
private class ByteBuf {
    private var buf = ByteArray(1024)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n <= buf.size) return
        var cap = buf.size
        while (cap < size + n) cap = cap * 2
        buf = buf.copyOf(cap)
    }

    fun u16(v: Int) {
        ensure(2)
        buf[size] = (v and 0xFF).toByte()
        buf[size + 1] = ((v ushr 8) and 0xFF).toByte()
        size += 2
    }

    fun u32(v: Int) {
        ensure(4)
        buf[size] = (v and 0xFF).toByte()
        buf[size + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[size + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[size + 3] = ((v ushr 24) and 0xFF).toByte()
        size += 4
    }

    fun bytes(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

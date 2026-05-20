// Ogg Vorbis audio decoder - Kotlin port of stb_vorbis v1.22
// Original C version: http://nothings.org/stb_vorbis/
//
// Original version written by Sean Barrett in 2007.
// Kotlin port: memory-based input only, float output only, no pushdata API.
//
// LICENSE: Public Domain / MIT (see original stb_vorbis.c)
//
// Limitations:
//   - floor 0 not supported (used in old ogg vorbis files pre-2004)
//   - lossless sample-truncation at beginning ignored
//   - cannot concatenate multiple vorbis streams
//   - sample positions are 32-bit, limiting seekable 192Khz
//       files to around 6 hours (Ogg supports 64-bit)

package com.mrpowergamerbr.butterscotchpreprocessor

import kotlin.math.*

// ========================================================================
// Error codes
// ========================================================================
enum class STBVorbisError(val code: Int) {
    NO_ERROR(0),
    NEED_MORE_DATA(1),
    INVALID_API_MIXING(2),
    OUT_OF_MEM(3),
    FEATURE_NOT_SUPPORTED(4),
    TOO_MANY_CHANNELS(5),
    FILE_OPEN_FAILURE(6),
    SEEK_WITHOUT_LENGTH(7),
    UNEXPECTED_EOF(10),
    SEEK_INVALID(11),
    INVALID_SETUP(20),
    INVALID_STREAM(21),
    MISSING_CAPTURE_PATTERN(30),
    INVALID_STREAM_STRUCTURE_VERSION(31),
    CONTINUED_PACKET_FLAG_INVALID(32),
    INCORRECT_STREAM_SERIAL_NUMBER(33),
    INVALID_FIRST_PAGE(34),
    BAD_PACKET_TYPE(35),
    CANT_FIND_LAST_PAGE(36),
    SEEK_FAILED(37),
    OGG_SKELETON_NOT_SUPPORTED(38)
}

// ========================================================================
// Public data classes
// ========================================================================
data class StbVorbisInfo(
    val sampleRate: Int,
    val channels: Int,
    val maxFrameSize: Int
)

// ========================================================================
// Main decoder class
// ========================================================================
class StbVorbis private constructor() {

    companion object {
        // Constants
        private const val MAX_BLOCKSIZE_LOG = 13
        private const val MAX_BLOCKSIZE = 1 shl MAX_BLOCKSIZE_LOG
        private const val STB_VORBIS_MAX_CHANNELS = 16
        private const val STB_VORBIS_FAST_HUFFMAN_LENGTH = 10
        private const val FAST_HUFFMAN_TABLE_SIZE = 1 shl STB_VORBIS_FAST_HUFFMAN_LENGTH
        private const val FAST_HUFFMAN_TABLE_MASK = FAST_HUFFMAN_TABLE_SIZE - 1
        private const val NO_CODE = 255
        private const val EOP = -1
        private const val INVALID_BITS = -1
        private const val PAGEFLAG_CONTINUED_PACKET = 1
        private const val PAGEFLAG_FIRST_PAGE = 2
        private const val PAGEFLAG_LAST_PAGE = 4
        private const val VORBIS_PACKET_ID = 1
        private const val VORBIS_PACKET_COMMENT = 3
        private const val VORBIS_PACKET_SETUP = 5
        private const val SAMPLE_UNKNOWN = 0xFFFFFFFFu
        private const val M_PI = 3.14159265358979323846264f

        // CRC32 table
        private const val CRC32_POLY = 0x04c11db7
        private val crcTable = IntArray(256)
        private var crcInitialized = false

        private fun crc32Init() {
            if (crcInitialized) return
            for (i in 0 until 256) {
                var s = i shl 24
                for (j in 0 until 8) {
                    s = (s shl 1) xor (if ((s.toLong() and 0xFFFFFFFFL) >= (1L shl 31)) CRC32_POLY else 0)
                }
                crcTable[i] = s
            }
            crcInitialized = true
        }

        private fun crc32Update(crc: Int, byte: Int): Int {
            return (crc shl 8) xor crcTable[(byte xor (crc ushr 24)) and 0xFF]
        }

        // inverse_db_table - block-copied from the specification
        private val inverseDbTable = floatArrayOf(
            1.0649863e-07f, 1.1341951e-07f, 1.2079015e-07f, 1.2863978e-07f,
            1.3699951e-07f, 1.4590251e-07f, 1.5538408e-07f, 1.6548181e-07f,
            1.7623575e-07f, 1.8768855e-07f, 1.9988561e-07f, 2.1287530e-07f,
            2.2670913e-07f, 2.4144197e-07f, 2.5713223e-07f, 2.7384213e-07f,
            2.9163793e-07f, 3.1059021e-07f, 3.3077411e-07f, 3.5226968e-07f,
            3.7516214e-07f, 3.9954229e-07f, 4.2550680e-07f, 4.5315863e-07f,
            4.8260743e-07f, 5.1396998e-07f, 5.4737065e-07f, 5.8294187e-07f,
            6.2082472e-07f, 6.6116941e-07f, 7.0413592e-07f, 7.4989464e-07f,
            7.9862701e-07f, 8.5052630e-07f, 9.0579828e-07f, 9.6466216e-07f,
            1.0273513e-06f, 1.0941144e-06f, 1.1652161e-06f, 1.2409384e-06f,
            1.3215816e-06f, 1.4074654e-06f, 1.4989305e-06f, 1.5963394e-06f,
            1.7000785e-06f, 1.8105592e-06f, 1.9282195e-06f, 2.0535261e-06f,
            2.1869758e-06f, 2.3290978e-06f, 2.4804557e-06f, 2.6416497e-06f,
            2.8133190e-06f, 2.9961443e-06f, 3.1908506e-06f, 3.3982101e-06f,
            3.6190449e-06f, 3.8542308e-06f, 4.1047004e-06f, 4.3714470e-06f,
            4.6555282e-06f, 4.9580707e-06f, 5.2802740e-06f, 5.6234160e-06f,
            5.9888572e-06f, 6.3780469e-06f, 6.7925283e-06f, 7.2339451e-06f,
            7.7040476e-06f, 8.2047000e-06f, 8.7378876e-06f, 9.3057248e-06f,
            9.9104632e-06f, 1.0554501e-05f, 1.1240392e-05f, 1.1970856e-05f,
            1.2748789e-05f, 1.3577278e-05f, 1.4459606e-05f, 1.5399272e-05f,
            1.6400004e-05f, 1.7465768e-05f, 1.8600792e-05f, 1.9809576e-05f,
            2.1096914e-05f, 2.2467911e-05f, 2.3928002e-05f, 2.5482978e-05f,
            2.7139006e-05f, 2.8902651e-05f, 3.0780908e-05f, 3.2781225e-05f,
            3.4911534e-05f, 3.7180282e-05f, 3.9596466e-05f, 4.2169667e-05f,
            4.4910090e-05f, 4.7828601e-05f, 5.0936773e-05f, 5.4246931e-05f,
            5.7772202e-05f, 6.1526565e-05f, 6.5524908e-05f, 6.9783085e-05f,
            7.4317983e-05f, 7.9147585e-05f, 8.4291040e-05f, 8.9768747e-05f,
            9.5602426e-05f, 0.00010181521f, 0.00010843174f, 0.00011547824f,
            0.00012298267f, 0.00013097477f, 0.00013948625f, 0.00014855085f,
            0.00015820453f, 0.00016848555f, 0.00017943469f, 0.00019109536f,
            0.00020351382f, 0.00021673929f, 0.00023082423f, 0.00024582449f,
            0.00026179955f, 0.00027881276f, 0.00029693158f, 0.00031622787f,
            0.00033677814f, 0.00035866388f, 0.00038197188f, 0.00040679456f,
            0.00043323036f, 0.00046138411f, 0.00049136745f, 0.00052329927f,
            0.00055730621f, 0.00059352311f, 0.00063209358f, 0.00067317058f,
            0.00071691700f, 0.00076350630f, 0.00081312324f, 0.00086596457f,
            0.00092223983f, 0.00098217216f, 0.0010459992f, 0.0011139742f,
            0.0011863665f, 0.0012634633f, 0.0013455702f, 0.0014330129f,
            0.0015261382f, 0.0016253153f, 0.0017309374f, 0.0018434235f,
            0.0019632195f, 0.0020908006f, 0.0022266726f, 0.0023713743f,
            0.0025254795f, 0.0026895994f, 0.0028643847f, 0.0030505286f,
            0.0032487691f, 0.0034598925f, 0.0036847358f, 0.0039241906f,
            0.0041792066f, 0.0044507950f, 0.0047400328f, 0.0050480668f,
            0.0053761186f, 0.0057254891f, 0.0060975636f, 0.0064938176f,
            0.0069158225f, 0.0073652516f, 0.0078438871f, 0.0083536271f,
            0.0088964928f, 0.009474637f, 0.010090352f, 0.010746080f,
            0.011444421f, 0.012188144f, 0.012980198f, 0.013823725f,
            0.014722068f, 0.015678791f, 0.016697687f, 0.017782797f,
            0.018938423f, 0.020169149f, 0.021479854f, 0.022875735f,
            0.024362330f, 0.025945531f, 0.027631618f, 0.029427276f,
            0.031339626f, 0.033376252f, 0.035545228f, 0.037855157f,
            0.040315199f, 0.042935108f, 0.045725273f, 0.048696758f,
            0.051861348f, 0.055231591f, 0.058820850f, 0.062643361f,
            0.066714279f, 0.071049749f, 0.075666962f, 0.080584227f,
            0.085821044f, 0.091398179f, 0.097337747f, 0.10366330f,
            0.11039993f, 0.11757434f, 0.12521498f, 0.13335215f,
            0.14201813f, 0.15124727f, 0.16107617f, 0.17154380f,
            0.18269168f, 0.19456402f, 0.20720788f, 0.22067342f,
            0.23501402f, 0.25028656f, 0.26655159f, 0.28387361f,
            0.30232132f, 0.32196786f, 0.34289114f, 0.36517414f,
            0.38890521f, 0.41417847f, 0.44109412f, 0.46975890f,
            0.50028648f, 0.53279791f, 0.56742212f, 0.60429640f,
            0.64356699f, 0.68538959f, 0.72993007f, 0.77736504f,
            0.82788260f, 0.88168307f, 0.9389798f, 1.0f
        )

        private val oggPageHeader = byteArrayOf(0x4f, 0x67, 0x67, 0x53)

        // bit_reverse: 32-bit bit reversal
        private fun bitReverse(n: Int): Int {
            var x = n
            x = ((x and 0xAAAAAAAA.toInt()) ushr 1) or ((x and 0x55555555) shl 1)
            x = ((x and 0xCCCCCCCC.toInt()) ushr 2) or ((x and 0x33333333) shl 2)
            x = ((x and 0xF0F0F0F0.toInt()) ushr 4) or ((x and 0x0F0F0F0F) shl 4)
            x = ((x and 0xFF00FF00.toInt()) ushr 8) or ((x and 0x00FF00FF) shl 8)
            return (x ushr 16) or (x shl 16)
        }

        // ilog: custom log2 where ilog(1)=1, ilog(2)=2, ilog(4)=3
        private fun ilog(n: Int): Int {
            val log2_4 = intArrayOf(0, 1, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4)
            if (0 > n) return 0 // signed n returns 0

            if ((1 shl 14) > n) {
                if ((1 shl 4) > n) return 0 + log2_4[n]
                else if ((1 shl 9) > n) return 5 + log2_4[n ushr 5]
                else return 10 + log2_4[n ushr 10]
            } else if ((1 shl 24) > n) {
                if ((1 shl 19) > n) return 15 + log2_4[n ushr 15]
                else return 20 + log2_4[n ushr 20]
            } else {
                if ((1 shl 29) > n) return 25 + log2_4[n ushr 25]
                else return 30 + log2_4[n ushr 30]
            }
        }

        // float32_unpack: unpack Vorbis float format
        private fun float32Unpack(x: Int): Float {
            val mantissa = x and 0x1fffff
            val sign = x and 0x80000000.toInt()
            val exp = (x and 0x7fe00000) ushr 21
            val res: Double = if (sign != 0) -mantissa.toDouble() else mantissa.toDouble()
            return (res * 2.0.pow(exp - 788)).toFloat()
        }

        // lookup1_values: formula implied by specification
        private fun lookup1Values(entries: Int, dim: Int): Int {
            var r = floor(exp(ln(entries.toFloat()) / dim)).toInt()
            if (floor((r + 1).toFloat().pow(dim.toFloat())).toInt() <= entries)
                ++r
            if ((r + 1).toFloat().pow(dim.toFloat()) <= entries)
                return -1
            if (floor(r.toFloat().pow(dim.toFloat())).toInt() > entries)
                return -1
            return r
        }

        // neighbors: find low and high neighbors
        private fun findNeighbors(x: ShortArray, n: Int, plow: IntArray, phigh: IntArray) {
            var low = -1
            var high = 65536
            for (i in 0 until n) {
                val xi = x[i].toInt() and 0xFFFF
                val xn = x[n].toInt() and 0xFFFF
                if (xi > low && xi < xn) { plow[0] = i; low = xi }
                if (xi < high && xi > xn) { phigh[0] = i; high = xi }
            }
        }

        /**
         * Open a Vorbis stream from memory.
         * Returns null on failure, with error code in the returned pair.
         */
        fun openMemory(data: ByteArray): Pair<StbVorbis?, STBVorbisError> {
            val p = StbVorbis()
            p.vorbisInit()
            p.stream = data
            p.streamPosition = 0
            p.streamEnd = data.size
            p.streamLen = data.size

            if (p.startDecoder()) {
                p.vorbisPumpFirstFrame()
                return Pair(p, STBVorbisError.NO_ERROR)
            }
            val err = p.lastError
            // No need for explicit deinit in Kotlin - GC handles it
            return Pair(null, err)
        }
    }

    // ====================================================================
    // Internal data structures
    // ====================================================================

    private class Codebook {
        var dimensions = 0
        var entries = 0
        var codewordLengths: ByteArray? = null
        var minimumValue = 0f
        var deltaValue = 0f
        var valueBits = 0
        var lookupType = 0
        var sequenceP = 0
        var sparse = 0
        var lookupValues = 0
        var multiplicands: FloatArray? = null
        var codewords: IntArray? = null
        var fastHuffman = ShortArray(FAST_HUFFMAN_TABLE_SIZE) { -1 }
        var sortedCodewords: IntArray? = null
        var sortedValues: IntArray? = null // note: index 0 corresponds to sorted_values[0] in C (the [-1] sentinel is handled separately)
        var sortedValuesSentinel = -1 // the C sorted_values[-1]
        var sortedEntries = 0
    }

    private class Floor1 {
        var partitions = 0
        var partitionClassList = IntArray(32)
        var classDimensions = IntArray(16)
        var classSubclasses = IntArray(16)
        var classMasterbooks = IntArray(16)
        var subclassBooks = Array(16) { ShortArray(8) }
        var xList = ShortArray(31 * 8 + 2)
        var sortedOrder = IntArray(31 * 8 + 2)
        var neighbors = Array(31 * 8 + 2) { IntArray(2) }
        var floor1Multiplier = 0
        var rangebits = 0
        var values = 0
    }

    private class Residue {
        var begin = 0
        var end = 0
        var partSize = 0
        var classifications = 0
        var classbook = 0
        var classdata: Array<ByteArray?>? = null
        var residueBooks: Array<ShortArray>? = null
    }

    private class MappingChannel {
        var magnitude = 0
        var angle = 0
        var mux = 0
    }

    private class Mapping {
        var couplingSteps = 0
        var chan: Array<MappingChannel>? = null
        var submaps = 0
        var submapFloor = IntArray(15)
        var submapResidue = IntArray(15)
    }

    private class Mode {
        var blockflag = 0
        var mapping = 0
        var windowtype = 0
        var transformtype = 0
    }

    private class ProbedPage {
        var pageStart = 0
        var pageEnd = 0
        var lastDecodedSample = 0
    }

    // ====================================================================
    // State fields
    // ====================================================================

    // user-accessible info
    var sampleRate = 0; private set
    var channels = 0; private set

    // input config (memory only)
    private var stream: ByteArray = ByteArray(0)
    private var streamPosition = 0
    private var streamEnd = 0
    private var streamLen = 0

    // the page to seek to when seeking to start, may be zero
    private var firstAudioPageOffset = 0

    // p_first is the page on which the first audio packet ends
    private var pFirst = ProbedPage()
    private var pLast = ProbedPage()

    // run-time results
    private var eof = false
    var lastError = STBVorbisError.NO_ERROR; private set

    // header info
    private var blocksize = IntArray(2)
    private var blocksize0 = 0
    private var blocksize1 = 0
    private var codebookCount = 0
    private var codebooks: Array<Codebook>? = null
    private var floorCount = 0
    private var floorTypes = IntArray(64)
    private var floorConfig: Array<Floor1?>? = null
    private var residueCount = 0
    private var residueTypes = IntArray(64)
    private var residueConfig: Array<Residue>? = null
    private var mappingCount = 0
    private var mappings: Array<Mapping>? = null
    private var modeCount = 0
    private var modeConfig = Array(64) { Mode() }

    private var totalSamples = 0

    // decode buffer
    private var channelBuffers = arrayOfNulls<FloatArray>(STB_VORBIS_MAX_CHANNELS)
    private var outputs = arrayOfNulls<FloatArray>(STB_VORBIS_MAX_CHANNELS)
    private var outputOffsets = IntArray(STB_VORBIS_MAX_CHANNELS)

    private var previousWindow = arrayOfNulls<FloatArray>(STB_VORBIS_MAX_CHANNELS)
    private var previousLength = 0

    // deferred floor (finalY)
    private var finalY = arrayOfNulls<ShortArray>(STB_VORBIS_MAX_CHANNELS)

    private var currentLoc = 0 // sample location of next frame to decode
    private var currentLocValid = false

    // per-blocksize precomputed data
    // twiddle factors
    private var tA = arrayOfNulls<FloatArray>(2)
    private var tB = arrayOfNulls<FloatArray>(2)
    private var tC = arrayOfNulls<FloatArray>(2)
    private var windowData = arrayOfNulls<FloatArray>(2)
    private var bitReverseData = arrayOfNulls<IntArray>(2) // using Int instead of UShort for simplicity

    // current page/packet/segment streaming info
    private var serial = 0
    private var lastPage = 0
    private var segmentCount = 0
    private var segments = IntArray(255)
    private var pageFlag = 0
    private var bytesInSeg = 0
    private var firstDecode = false
    private var nextSeg = 0
    private var lastSeg = false
    private var lastSegWhich = 0
    private var acc = 0
    private var validBits = 0
    private var packetBytes = 0
    private var endSegWithKnownLoc = 0
    private var knownLocForPacket = 0
    private var discardSamplesDeferred = 0
    private var samplesOutput = 0

    // sample-access
    private var channelBufferStart = 0
    private var channelBufferEnd = 0

    // ====================================================================
    // Initialization
    // ====================================================================

    private fun vorbisInit() {
        eof = false
        lastError = STBVorbisError.NO_ERROR
        codebooks = null
    }

    // ====================================================================
    // Stream reading (memory only)
    // ====================================================================

    private fun get8(): Int {
        if (streamPosition >= streamEnd) { eof = true; return 0 }
        return stream[streamPosition++].toInt() and 0xFF
    }

    private fun get32(): Int {
        var x = get8()
        x += get8() shl 8
        x += get8() shl 16
        x += get8() shl 24
        return x
    }

    private fun getn(data: ByteArray, offset: Int, n: Int): Boolean {
        if (streamPosition + n > streamEnd) { eof = true; return false }
        stream.copyInto(data, offset, streamPosition, streamPosition + n)
        streamPosition += n
        return true
    }

    private fun getn(data: IntArray, offset: Int, n: Int): Boolean {
        if (streamPosition + n > streamEnd) { eof = true; return false }
        for (i in 0 until n) {
            data[offset + i] = stream[streamPosition++].toInt() and 0xFF
        }
        return true
    }

    private fun skip(n: Int) {
        streamPosition += n
        if (streamPosition >= streamEnd) eof = true
    }

    private fun setFileOffset(loc: Int): Boolean {
        eof = false
        if (loc >= streamEnd || 0 > loc) {
            streamPosition = streamEnd
            eof = true
            return false
        } else {
            streamPosition = loc
            return true
        }
    }

    private fun getFileOffset(): Int {
        return streamPosition
    }

    // ====================================================================
    // Error handling
    // ====================================================================

    private fun setError(e: STBVorbisError): Boolean {
        lastError = e
        return false
    }

    // ====================================================================
    // OGG page parsing
    // ====================================================================

    private fun capturePattern(): Boolean {
        if (0x4f != get8()) return false
        if (0x67 != get8()) return false
        if (0x67 != get8()) return false
        if (0x53 != get8()) return false
        return true
    }

    private fun startPageNoCapturePattern(): Boolean {
        if (firstDecode) {
            pFirst.pageStart = getFileOffset() - 4
        }
        // stream structure version
        if (0 != get8()) return setError(STBVorbisError.INVALID_STREAM_STRUCTURE_VERSION)
        // header flag
        pageFlag = get8()
        // absolute granule position
        val loc0 = get32()
        val loc1 = get32()
        // stream serial number - vorbis doesn't interleave, so discard
        get32()
        // page sequence number
        val n = get32()
        lastPage = n
        // CRC32
        get32()
        // page_segments
        segmentCount = get8()
        if (!getn(segments, 0, segmentCount))
            return setError(STBVorbisError.UNEXPECTED_EOF)
        // assume we don't know the sample position of any segments
        endSegWithKnownLoc = -2
        if (loc0 != -1 || loc1 != -1) {
            // determine which packet is the last one that will complete
            var i = segmentCount - 1
            while (i >= 0) {
                if (255 > segments[i])
                    break
                --i
            }
            // 'i' is now the index of the last segment of a packet that ends
            if (i >= 0) {
                endSegWithKnownLoc = i
                knownLocForPacket = loc0
            }
        }
        if (firstDecode) {
            var len = 0
            for (i in 0 until segmentCount)
                len += segments[i]
            len += 27 + segmentCount
            pFirst.pageEnd = pFirst.pageStart + len
            pFirst.lastDecodedSample = loc0
        }
        nextSeg = 0
        return true
    }

    private fun startPage(): Boolean {
        if (!capturePattern()) return setError(STBVorbisError.MISSING_CAPTURE_PATTERN)
        return startPageNoCapturePattern()
    }

    private fun startPacket(): Boolean {
        while (nextSeg == -1) {
            if (!startPage()) return false
            if (pageFlag and PAGEFLAG_CONTINUED_PACKET != 0)
                return setError(STBVorbisError.CONTINUED_PACKET_FLAG_INVALID)
        }
        lastSeg = false
        validBits = 0
        packetBytes = 0
        bytesInSeg = 0
        return true
    }

    private fun maybeStartPacket(): Boolean {
        if (nextSeg == -1) {
            val x = get8()
            if (eof) return false
            if (0x4f != x) return setError(STBVorbisError.MISSING_CAPTURE_PATTERN)
            if (0x67 != get8()) return setError(STBVorbisError.MISSING_CAPTURE_PATTERN)
            if (0x67 != get8()) return setError(STBVorbisError.MISSING_CAPTURE_PATTERN)
            if (0x53 != get8()) return setError(STBVorbisError.MISSING_CAPTURE_PATTERN)
            if (!startPageNoCapturePattern()) return false
            if (pageFlag and PAGEFLAG_CONTINUED_PACKET != 0) {
                lastSeg = false
                bytesInSeg = 0
                return setError(STBVorbisError.CONTINUED_PACKET_FLAG_INVALID)
            }
        }
        return startPacket()
    }

    private fun nextSegment(): Int {
        if (lastSeg) return 0
        if (nextSeg == -1) {
            lastSegWhich = segmentCount - 1
            if (!startPage()) { lastSeg = true; return 0 }
            if (pageFlag and PAGEFLAG_CONTINUED_PACKET == 0) { setError(STBVorbisError.CONTINUED_PACKET_FLAG_INVALID); return 0 }
        }
        val len = segments[nextSeg++]
        if (255 > len) {
            lastSeg = true
            lastSegWhich = nextSeg - 1
        }
        if (nextSeg >= segmentCount)
            nextSeg = -1
        bytesInSeg = len
        return len
    }

    // ====================================================================
    // Bit reading
    // ====================================================================

    private fun get8PacketRaw(): Int {
        if (bytesInSeg == 0) {
            if (lastSeg) return EOP
            else if (nextSegment() == 0) return EOP
        }
        --bytesInSeg
        ++packetBytes
        return get8()
    }

    private fun get8Packet(): Int {
        val x = get8PacketRaw()
        validBits = 0
        return x
    }

    private fun get32Packet(): Int {
        var x = get8Packet()
        x += get8Packet() shl 8
        x += get8Packet() shl 16
        x += get8Packet() shl 24
        return x
    }

    private fun flushPacket() {
        while (get8PacketRaw() != EOP) { /* consume */ }
    }

    private fun getBits(n: Int): Int {
        if (0 > validBits) return 0
        if (n > validBits) {
            if (24 < n) {
                val z = getBits(24)
                return z + (getBits(n - 24) shl 24)
            }
            if (validBits == 0) acc = 0
            while (n > validBits) {
                val z = get8PacketRaw()
                if (z == EOP) {
                    validBits = INVALID_BITS
                    return 0
                }
                acc += z shl validBits
                validBits += 8
            }
        }
        val z = acc and ((1 shl n) - 1)
        acc = acc ushr n
        validBits -= n
        return z
    }

    private fun prepHuffman() {
        if (24 >= validBits) {
            if (validBits == 0) acc = 0
            do {
                if (lastSeg && bytesInSeg == 0) return
                val z = get8PacketRaw()
                if (z == EOP) return
                acc += z shl validBits
                validBits += 8
            } while (24 >= validBits)
        }
    }

    // ====================================================================
    // Huffman / Codebook
    // ====================================================================

    private fun addEntry(c: Codebook, huffCode: Int, symbol: Int, count: Int, len: Int, values: IntArray?) {
        if (c.sparse == 0) {
            c.codewords!![symbol] = huffCode
        } else {
            c.codewords!![count] = huffCode
            c.codewordLengths!![count] = len.toByte()
            values!![count] = symbol
        }
    }

    private fun computeCodewords(c: Codebook, len: ByteArray, n: Int, values: IntArray?): Boolean {
        var m = 0
        val available = IntArray(32)

        // find the first entry
        var k = 0
        while (k < n) {
            if ((len[k].toInt() and 0xFF) < NO_CODE) break
            ++k
        }
        if (k == n) return true
        // add to the list
        addEntry(c, 0, k, m++, len[k].toInt() and 0xFF, values)
        // add all available leaves
        for (i in 1..(len[k].toInt() and 0xFF))
            available[i] = 1 shl (32 - i)

        for (i in k + 1 until n) {
            val z0 = len[i].toInt() and 0xFF
            if (z0 == NO_CODE) continue
            var z = z0
            while (z > 0 && available[z] == 0) --z
            if (z == 0) return false
            val res = available[z]
            available[z] = 0
            addEntry(c, bitReverse(res), i, m++, len[i].toInt() and 0xFF, values)
            if (z != z0) {
                var y = z0
                while (y > z) {
                    available[y] = res + (1 shl (32 - y))
                    --y
                }
            }
        }
        return true
    }

    private fun computeAcceleratedHuffman(c: Codebook) {
        for (i in 0 until FAST_HUFFMAN_TABLE_SIZE)
            c.fastHuffman[i] = -1

        var len = if (c.sparse != 0) c.sortedEntries else c.entries
        if (32767 < len) len = 32767 // largest possible value we can encode in Short
        for (i in 0 until len) {
            if ((c.codewordLengths!![i].toInt() and 0xFF) <= STB_VORBIS_FAST_HUFFMAN_LENGTH) {
                var z = if (c.sparse != 0) bitReverse(c.sortedCodewords!![i]) else c.codewords!![i]
                while (FAST_HUFFMAN_TABLE_SIZE > z) {
                    c.fastHuffman[z] = i.toShort()
                    z += 1 shl (c.codewordLengths!![i].toInt() and 0xFF)
                }
            }
        }
    }

    private fun includeInSort(c: Codebook, len: Int): Boolean {
        if (c.sparse != 0) return true
        if (len == NO_CODE) return false
        if (STB_VORBIS_FAST_HUFFMAN_LENGTH < len) return true
        return false
    }

    private fun computeSortedHuffman(c: Codebook, lengths: ByteArray, values: IntArray?) {
        // build a list of all the entries
        if (c.sparse == 0) {
            var k = 0
            for (i in 0 until c.entries) {
                if (includeInSort(c, lengths[i].toInt() and 0xFF))
                    c.sortedCodewords!![k++] = bitReverse(c.codewords!![i])
            }
        } else {
            for (i in 0 until c.sortedEntries)
                c.sortedCodewords!![i] = bitReverse(c.codewords!![i])
        }

        // sort
        val sc = c.sortedCodewords!!
        val sortedPart = sc.copyOfRange(0, c.sortedEntries)
        // Use unsigned comparison for sorting
        sortedPart.sortedArray().copyInto(sc, 0) // This won't do unsigned sort properly
        // Need to do unsigned sort
        val indices = (0 until c.sortedEntries).sortedWith(Comparator { a, b ->
            val ua = sortedPart[a].toLong() and 0xFFFFFFFFL
            val ub = sortedPart[b].toLong() and 0xFFFFFFFFL
            ua.compareTo(ub)
        })
        val sorted = IntArray(c.sortedEntries) { sortedPart[indices[it]] }
        sorted.copyInto(sc, 0)

        sc[c.sortedEntries] = -1 // 0xffffffff sentinel

        val len2 = if (c.sparse != 0) c.sortedEntries else c.entries
        for (i in 0 until len2) {
            val huffLen = if (c.sparse != 0) lengths[values!![i]].toInt() and 0xFF else lengths[i].toInt() and 0xFF
            if (includeInSort(c, huffLen)) {
                val code = bitReverse(c.codewords!![i])
                var x = 0
                var n = c.sortedEntries
                while (1 < n) {
                    val m = x + (n shr 1)
                    if ((sc[m].toLong() and 0xFFFFFFFFL) <= (code.toLong() and 0xFFFFFFFFL)) {
                        x = m
                        n -= (n shr 1)
                    } else {
                        n = n shr 1
                    }
                }
                if (c.sparse != 0) {
                    c.sortedValues!![x] = values!![i]
                    c.codewordLengths!![x] = huffLen.toByte()
                } else {
                    c.sortedValues!![x] = i
                }
            }
        }
    }

    private fun vorbisValidate(data: ByteArray, offset: Int): Boolean {
        return data[offset + 0] == 'v'.code.toByte() &&
               data[offset + 1] == 'o'.code.toByte() &&
               data[offset + 2] == 'r'.code.toByte() &&
               data[offset + 3] == 'b'.code.toByte() &&
               data[offset + 4] == 'i'.code.toByte() &&
               data[offset + 5] == 's'.code.toByte()
    }

    private fun vorbisValidateInts(data: IntArray, offset: Int): Boolean {
        return data[offset + 0] == 'v'.code &&
               data[offset + 1] == 'o'.code &&
               data[offset + 2] == 'r'.code &&
               data[offset + 3] == 'b'.code &&
               data[offset + 4] == 'i'.code &&
               data[offset + 5] == 's'.code
    }

    // ====================================================================
    // Twiddle factors / window / bitreverse
    // ====================================================================

    private fun computeTwiddleFactors(n: Int, a: FloatArray, b: FloatArray, c: FloatArray) {
        val n4 = n shr 2
        val n8 = n shr 3
        var k2 = 0
        for (k in 0 until n4) {
            a[k2] = cos(4.0f * k * M_PI / n)
            a[k2 + 1] = -sin(4.0f * k * M_PI / n)
            b[k2] = cos((k2 + 1).toFloat() * M_PI / n / 2) * 0.5f
            b[k2 + 1] = sin((k2 + 1).toFloat() * M_PI / n / 2) * 0.5f
            k2 += 2
        }
        k2 = 0
        for (k in 0 until n8) {
            c[k2] = cos(2.0f * (k2 + 1) * M_PI / n)
            c[k2 + 1] = -sin(2.0f * (k2 + 1) * M_PI / n)
            k2 += 2
        }
    }

    private fun computeWindow(n: Int, window: FloatArray) {
        val n2 = n shr 1
        for (i in 0 until n2) {
            window[i] = sin(
                0.5f * M_PI * (sin((i + 0.5f) / n2 * 0.5f * M_PI).let { it * it })
            )
        }
    }

    private fun computeBitreverse(n: Int, rev: IntArray) {
        val ld = ilog(n) - 1
        val n8 = n shr 3
        for (i in 0 until n8)
            rev[i] = (bitReverse(i) ushr (32 - ld + 3)) shl 2
    }

    private fun initBlocksize(b: Int, n: Int): Boolean {
        val n2 = n shr 1
        val n4 = n shr 2
        val n8 = n shr 3
        tA[b] = FloatArray(n2)
        tB[b] = FloatArray(n2)
        tC[b] = FloatArray(n4)
        computeTwiddleFactors(n, tA[b]!!, tB[b]!!, tC[b]!!)
        windowData[b] = FloatArray(n2)
        computeWindow(n, windowData[b]!!)
        bitReverseData[b] = IntArray(n8)
        computeBitreverse(n, bitReverseData[b]!!)
        return true
    }

    // ====================================================================
    // Codebook decode
    // ====================================================================

    private fun codebookDecodeScalarRaw(c: Codebook): Int {
        prepHuffman()

        if (c.codewords == null && c.sortedCodewords == null)
            return -1

        if (if (8 < c.entries) c.sortedCodewords != null else c.codewords == null) {
            // binary search
            val code = bitReverse(acc)
            var x = 0
            var n = c.sortedEntries

            while (1 < n) {
                val m = x + (n shr 1)
                if ((c.sortedCodewords!![m].toLong() and 0xFFFFFFFFL) <= (code.toLong() and 0xFFFFFFFFL)) {
                    x = m
                    n -= (n shr 1)
                } else {
                    n = n shr 1
                }
            }
            if (c.sparse == 0) x = c.sortedValues!![x]
            val len = c.codewordLengths!![x].toInt() and 0xFF
            if (validBits >= len) {
                acc = acc ushr len
                validBits -= len
                return x
            }
            validBits = 0
            return -1
        }

        // if small, linear search
        for (i in 0 until c.entries) {
            if ((c.codewordLengths!![i].toInt() and 0xFF) == NO_CODE) continue
            if (c.codewords!![i] == (acc and ((1 shl (c.codewordLengths!![i].toInt() and 0xFF)) - 1))) {
                val cwl = c.codewordLengths!![i].toInt() and 0xFF
                if (validBits >= cwl) {
                    acc = acc ushr cwl
                    validBits -= cwl
                    return i
                }
                validBits = 0
                return -1
            }
        }

        setError(STBVorbisError.INVALID_STREAM)
        validBits = 0
        return -1
    }

    // DECODE_RAW inlined as a function
    private fun decodeRaw(c: Codebook): Int {
        if (STB_VORBIS_FAST_HUFFMAN_LENGTH > validBits) prepHuffman()
        var i = acc and FAST_HUFFMAN_TABLE_MASK
        i = c.fastHuffman[i].toInt()
        if (i >= 0) {
            val n = c.codewordLengths!![i].toInt() and 0xFF
            acc = acc ushr n
            validBits -= n
            if (0 > validBits) { validBits = 0; return -1 }
        } else {
            i = codebookDecodeScalarRaw(c)
        }
        return i
    }

    // DECODE: decodeRaw + sparse value lookup
    private fun decode(c: Codebook): Int {
        var v = decodeRaw(c)
        if (c.sparse != 0 && v >= 0) v = c.sortedValues!![v]
        return v
    }

    // DECODE_VQ: same as DECODE_RAW for non-divides-in-codebook
    private fun decodeVQ(c: Codebook): Int {
        return decodeRaw(c)
    }

    private fun codebookDecodeStart(c: Codebook): Int {
        if (c.lookupType == 0) {
            setError(STBVorbisError.INVALID_STREAM)
            return -1
        }
        val z = decodeVQ(c)
        if (0 > z) {
            if (bytesInSeg == 0)
                if (lastSeg)
                    return z
            setError(STBVorbisError.INVALID_STREAM)
        }
        return z
    }

    private fun codebookDecode(c: Codebook, output: FloatArray, outputOffset: Int, len0: Int): Boolean {
        val z = codebookDecodeStart(c)
        if (0 > z) return false
        var len = len0
        if (c.dimensions < len) len = c.dimensions

        val zOff = z * c.dimensions
        val mults = c.multiplicands!!
        if (c.sequenceP != 0) {
            var last = 0f
            for (i in 0 until len) {
                val v = mults[zOff + i] + last
                output[outputOffset + i] += v
                last = v + c.minimumValue
            }
        } else {
            for (i in 0 until len) {
                output[outputOffset + i] += mults[zOff + i]
            }
        }
        return true
    }

    private fun codebookDecodeStep(c: Codebook, output: FloatArray, outputOffset: Int, len0: Int, step: Int): Boolean {
        val z = codebookDecodeStart(c)
        if (0 > z) return false
        var len = len0
        if (c.dimensions < len) len = c.dimensions

        val zOff = z * c.dimensions
        val mults = c.multiplicands!!
        var last = 0f
        for (i in 0 until len) {
            val v = mults[zOff + i] + last
            output[outputOffset + i * step] += v
            if (c.sequenceP != 0) last = v
        }
        return true
    }

    private fun codebookDecodeDeinterleaveRepeat(
        c: Codebook, outputs: Array<FloatArray?>, ch: Int,
        cInterP: IntArray, pInterP: IntArray, len: Int, totalDecode0: Int
    ): Boolean {
        var cInter = cInterP[0]
        var pInter = pInterP[0]
        var totalDecode = totalDecode0

        if (c.lookupType == 0) return setError(STBVorbisError.INVALID_STREAM)

        while (0 < totalDecode) {
            val z0 = decodeVQ(c)
            if (0 > z0) {
                if (bytesInSeg == 0 && lastSeg) return false
                return setError(STBVorbisError.INVALID_STREAM)
            }

            var effective = c.dimensions
            if (cInter + pInter * ch + effective > len * ch) {
                effective = len * ch - (pInter * ch - cInter)
            }

            val z = z0 * c.dimensions
            val mults = c.multiplicands!!
            if (c.sequenceP != 0) {
                var last = 0f
                for (i in 0 until effective) {
                    val v = mults[z + i] + last
                    outputs[cInter]?.let { it[pInter] += v }
                    if (++cInter == ch) { cInter = 0; ++pInter }
                    last = v
                }
            } else {
                for (i in 0 until effective) {
                    val v = mults[z + i]
                    outputs[cInter]?.let { it[pInter] += v }
                    if (++cInter == ch) { cInter = 0; ++pInter }
                }
            }

            totalDecode -= effective
        }
        cInterP[0] = cInter
        pInterP[0] = pInter
        return true
    }

    // ====================================================================
    // Floor decoding
    // ====================================================================

    private fun predictPoint(x: Int, x0: Int, x1: Int, y0: Int, y1: Int): Int {
        val dy = y1 - y0
        val adx = x1 - x0
        val err = abs(dy) * (x - x0)
        val off = err / adx
        return if (0 > dy) y0 - off else y0 + off
    }

    // LINE_OP: deferred floor mode -> a *= b
    private fun drawLine(output: FloatArray, x0: Int, y0: Int, x1: Int, y1: Int, n: Int) {
        val dy = y1 - y0
        val adx = x1 - x0
        var ady = abs(dy)
        val base = dy / adx
        val sy = if (0 > dy) base - 1 else base + 1
        ady -= abs(base) * adx
        var x = x0
        var y = y0
        var err = 0
        val ex1 = if (n < x1) n else x1
        if (ex1 > x) {
            output[x] *= inverseDbTable[y and 255]
            x++
            while (ex1 > x) {
                err += ady
                if (err >= adx) {
                    err -= adx
                    y += sy
                } else {
                    y += base
                }
                output[x] *= inverseDbTable[y and 255]
                x++
            }
        }
    }

    private fun residueDecode(book: Codebook, target: FloatArray, offset: Int, n: Int, rtype: Int): Boolean {
        if (rtype == 0) {
            val step = n / book.dimensions
            for (k in 0 until step) {
                if (!codebookDecodeStep(book, target, offset + k, n - offset - k, step))
                    return false
            }
        } else {
            var k = 0
            var off = offset
            while (n > k) {
                if (!codebookDecode(book, target, off, n - k))
                    return false
                k += book.dimensions
                off += book.dimensions
            }
        }
        return true
    }

    // n is 1/2 of the blocksize
    private fun decodeResidue(residueBuffers: Array<FloatArray?>, ch: Int, n: Int, rn: Int, doNotDecode: BooleanArray) {
        val r = residueConfig!![rn]
        val rtype = residueTypes[rn]
        val c = r.classbook
        val classwords = codebooks!![c].dimensions
        val actualSize = if (rtype == 2) n * 2 else n
        val limitRBegin = if (actualSize > r.begin) r.begin else actualSize
        val limitREnd = if (actualSize > r.end) r.end else actualSize
        val nRead = limitREnd - limitRBegin
        val partRead = nRead / r.partSize

        val partClassdata = Array<Array<ByteArray?>>(channels) { arrayOfNulls(partRead) }

        for (i in 0 until ch)
            if (!doNotDecode[i])
                residueBuffers[i]?.fill(0f, 0, n)

        if (rtype == 2 && ch != 1) {
            var j = 0
            while (j < ch) {
                if (!doNotDecode[j]) break
                ++j
            }
            if (j == ch) return

            for (pass in 0 until 8) {
                var pcount = 0
                var classSet = 0
                if (ch == 2) {
                    while (partRead > pcount) {
                        var z = r.begin + pcount * r.partSize
                        var cInter = z and 1
                        var pInter = z shr 1
                        if (pass == 0) {
                            val cb = codebooks!![r.classbook]
                            val q = decode(cb)
                            if (q == EOP) return
                            partClassdata[0][classSet] = r.classdata!![q]
                        }
                        for (i in 0 until classwords) {
                            if (partRead <= pcount) break
                            z = r.begin + pcount * r.partSize
                            val ci = partClassdata[0][classSet]!![i].toInt() and 0xFF
                            val b = r.residueBooks!![ci][pass].toInt()
                            if (b >= 0) {
                                val book = codebooks!![b]
                                val cInterArr = intArrayOf(cInter)
                                val pInterArr = intArrayOf(pInter)
                                if (!codebookDecodeDeinterleaveRepeat(book, residueBuffers, ch, cInterArr, pInterArr, n, r.partSize))
                                    return
                                cInter = cInterArr[0]
                                pInter = pInterArr[0]
                            } else {
                                z += r.partSize
                                cInter = z and 1
                                pInter = z shr 1
                            }
                            ++pcount
                        }
                        ++classSet
                    }
                } else if (2 < ch) {
                    while (partRead > pcount) {
                        var z = r.begin + pcount * r.partSize
                        var cInter = z % ch
                        var pInter = z / ch
                        if (pass == 0) {
                            val cb = codebooks!![r.classbook]
                            val q = decode(cb)
                            if (q == EOP) return
                            partClassdata[0][classSet] = r.classdata!![q]
                        }
                        for (i in 0 until classwords) {
                            if (partRead <= pcount) break
                            z = r.begin + pcount * r.partSize
                            val ci = partClassdata[0][classSet]!![i].toInt() and 0xFF
                            val b = r.residueBooks!![ci][pass].toInt()
                            if (b >= 0) {
                                val book = codebooks!![b]
                                val cInterArr = intArrayOf(cInter)
                                val pInterArr = intArrayOf(pInter)
                                if (!codebookDecodeDeinterleaveRepeat(book, residueBuffers, ch, cInterArr, pInterArr, n, r.partSize))
                                    return
                                cInter = cInterArr[0]
                                pInter = pInterArr[0]
                            } else {
                                z += r.partSize
                                cInter = z % ch
                                pInter = z / ch
                            }
                            ++pcount
                        }
                        ++classSet
                    }
                }
            }
            return
        }

        for (pass in 0 until 8) {
            var pcount = 0
            var classSet = 0
            while (partRead > pcount) {
                if (pass == 0) {
                    for (j in 0 until ch) {
                        if (!doNotDecode[j]) {
                            val cb = codebooks!![r.classbook]
                            val temp = decode(cb)
                            if (temp == EOP) return
                            partClassdata[j][classSet] = r.classdata!![temp]
                        }
                    }
                }
                for (i in 0 until classwords) {
                    if (partRead <= pcount) break
                    for (j in 0 until ch) {
                        if (!doNotDecode[j]) {
                            val ci = partClassdata[j][classSet]!![i].toInt() and 0xFF
                            val b = r.residueBooks!![ci][pass].toInt()
                            if (b >= 0) {
                                val target = residueBuffers[j]!!
                                val offset = r.begin + pcount * r.partSize
                                val nn = r.partSize
                                val book = codebooks!![b]
                                if (!residueDecode(book, target, offset, nn, rtype))
                                    return
                            }
                        }
                    }
                    ++pcount
                }
                ++classSet
            }
        }
    }

    // ====================================================================
    // Inverse MDCT
    // ====================================================================

    private fun imdctStep3Iter0Loop(n: Int, e: FloatArray, iOff: Int, kOff: Int, a: FloatArray) {
        var ee0 = iOff
        var ee2 = ee0 + kOff
        var aIdx = 0

        for (i in 0 until (n shr 2)) {
            var k00_20 = e[ee0] - e[ee2]
            var k01_21 = e[ee0 - 1] - e[ee2 - 1]
            e[ee0] += e[ee2]
            e[ee0 - 1] += e[ee2 - 1]
            e[ee2] = k00_20 * a[aIdx] - k01_21 * a[aIdx + 1]
            e[ee2 - 1] = k01_21 * a[aIdx] + k00_20 * a[aIdx + 1]
            aIdx += 8

            k00_20 = e[ee0 - 2] - e[ee2 - 2]
            k01_21 = e[ee0 - 3] - e[ee2 - 3]
            e[ee0 - 2] += e[ee2 - 2]
            e[ee0 - 3] += e[ee2 - 3]
            e[ee2 - 2] = k00_20 * a[aIdx] - k01_21 * a[aIdx + 1]
            e[ee2 - 3] = k01_21 * a[aIdx] + k00_20 * a[aIdx + 1]
            aIdx += 8

            k00_20 = e[ee0 - 4] - e[ee2 - 4]
            k01_21 = e[ee0 - 5] - e[ee2 - 5]
            e[ee0 - 4] += e[ee2 - 4]
            e[ee0 - 5] += e[ee2 - 5]
            e[ee2 - 4] = k00_20 * a[aIdx] - k01_21 * a[aIdx + 1]
            e[ee2 - 5] = k01_21 * a[aIdx] + k00_20 * a[aIdx + 1]
            aIdx += 8

            k00_20 = e[ee0 - 6] - e[ee2 - 6]
            k01_21 = e[ee0 - 7] - e[ee2 - 7]
            e[ee0 - 6] += e[ee2 - 6]
            e[ee0 - 7] += e[ee2 - 7]
            e[ee2 - 6] = k00_20 * a[aIdx] - k01_21 * a[aIdx + 1]
            e[ee2 - 7] = k01_21 * a[aIdx] + k00_20 * a[aIdx + 1]
            aIdx += 8

            ee0 -= 8
            ee2 -= 8
        }
    }

    private fun imdctStep3InnerRLoop(lim: Int, e: FloatArray, d0: Int, kOff: Int, a: FloatArray, k1: Int) {
        var e0 = d0
        var e2 = e0 + kOff
        var aIdx = 0

        for (i in 0 until (lim shr 2)) {
            var k00_20 = e[e0] - e[e2]
            var k01_21 = e[e0 - 1] - e[e2 - 1]
            e[e0] += e[e2]
            e[e0 - 1] += e[e2 - 1]
            e[e2] = k00_20 * a[aIdx] - k01_21 * a[aIdx + 1]
            e[e2 - 1] = k01_21 * a[aIdx] + k00_20 * a[aIdx + 1]
            aIdx += k1

            k00_20 = e[e0 - 2] - e[e2 - 2]
            k01_21 = e[e0 - 3] - e[e2 - 3]
            e[e0 - 2] += e[e2 - 2]
            e[e0 - 3] += e[e2 - 3]
            e[e2 - 2] = k00_20 * a[aIdx] - k01_21 * a[aIdx + 1]
            e[e2 - 3] = k01_21 * a[aIdx] + k00_20 * a[aIdx + 1]
            aIdx += k1

            k00_20 = e[e0 - 4] - e[e2 - 4]
            k01_21 = e[e0 - 5] - e[e2 - 5]
            e[e0 - 4] += e[e2 - 4]
            e[e0 - 5] += e[e2 - 5]
            e[e2 - 4] = k00_20 * a[aIdx] - k01_21 * a[aIdx + 1]
            e[e2 - 5] = k01_21 * a[aIdx] + k00_20 * a[aIdx + 1]
            aIdx += k1

            k00_20 = e[e0 - 6] - e[e2 - 6]
            k01_21 = e[e0 - 7] - e[e2 - 7]
            e[e0 - 6] += e[e2 - 6]
            e[e0 - 7] += e[e2 - 7]
            e[e2 - 6] = k00_20 * a[aIdx] - k01_21 * a[aIdx + 1]
            e[e2 - 7] = k01_21 * a[aIdx] + k00_20 * a[aIdx + 1]

            e0 -= 8
            e2 -= 8
            aIdx += k1
        }
    }

    private fun imdctStep3InnerSLoop(n: Int, e: FloatArray, iOff: Int, kOff: Int, a: FloatArray, aOff: Int, k0: Int) {
        val a0 = a[0]
        val a1 = a[1]
        val a2 = a[aOff]
        val a3 = a[aOff + 1]
        val a4 = a[aOff * 2]
        val a5 = a[aOff * 2 + 1]
        val a6 = a[aOff * 3]
        val a7 = a[aOff * 3 + 1]

        var ee0 = iOff
        var ee2 = ee0 + kOff

        for (i in 0 until n) {
            var k00 = e[ee0] - e[ee2]
            var k11 = e[ee0 - 1] - e[ee2 - 1]
            e[ee0] = e[ee0] + e[ee2]
            e[ee0 - 1] = e[ee0 - 1] + e[ee2 - 1]
            e[ee2] = k00 * a0 - k11 * a1
            e[ee2 - 1] = k11 * a0 + k00 * a1

            k00 = e[ee0 - 2] - e[ee2 - 2]
            k11 = e[ee0 - 3] - e[ee2 - 3]
            e[ee0 - 2] = e[ee0 - 2] + e[ee2 - 2]
            e[ee0 - 3] = e[ee0 - 3] + e[ee2 - 3]
            e[ee2 - 2] = k00 * a2 - k11 * a3
            e[ee2 - 3] = k11 * a2 + k00 * a3

            k00 = e[ee0 - 4] - e[ee2 - 4]
            k11 = e[ee0 - 5] - e[ee2 - 5]
            e[ee0 - 4] = e[ee0 - 4] + e[ee2 - 4]
            e[ee0 - 5] = e[ee0 - 5] + e[ee2 - 5]
            e[ee2 - 4] = k00 * a4 - k11 * a5
            e[ee2 - 5] = k11 * a4 + k00 * a5

            k00 = e[ee0 - 6] - e[ee2 - 6]
            k11 = e[ee0 - 7] - e[ee2 - 7]
            e[ee0 - 6] = e[ee0 - 6] + e[ee2 - 6]
            e[ee0 - 7] = e[ee0 - 7] + e[ee2 - 7]
            e[ee2 - 6] = k00 * a6 - k11 * a7
            e[ee2 - 7] = k11 * a6 + k00 * a7

            ee0 -= k0
            ee2 -= k0
        }
    }

    private fun iter54(z: FloatArray, zOff: Int) {
        val k00 = z[zOff] - z[zOff - 4]
        val y0 = z[zOff] + z[zOff - 4]
        val y2 = z[zOff - 2] + z[zOff - 6]
        val k22 = z[zOff - 2] - z[zOff - 6]

        z[zOff] = y0 + y2
        z[zOff - 2] = y0 - y2

        val k33 = z[zOff - 3] - z[zOff - 7]

        z[zOff - 4] = k00 + k33
        z[zOff - 6] = k00 - k33

        val k11 = z[zOff - 1] - z[zOff - 5]
        val y1 = z[zOff - 1] + z[zOff - 5]
        val y3 = z[zOff - 3] + z[zOff - 7]

        z[zOff - 1] = y1 + y3
        z[zOff - 3] = y1 - y3
        z[zOff - 5] = k11 - k22
        z[zOff - 7] = k11 + k22
    }

    private fun imdctStep3InnerSLoopLd654(n: Int, e: FloatArray, iOff: Int, a: FloatArray, baseN: Int) {
        val aOff = baseN shr 3
        val a2 = a[aOff]
        var z = iOff
        val base = z - 16 * n

        while (z > base) {
            var k00 = e[z] - e[z - 8]
            var k11 = e[z - 1] - e[z - 9]
            var l00 = e[z - 2] - e[z - 10]
            var l11 = e[z - 3] - e[z - 11]
            e[z] = e[z] + e[z - 8]
            e[z - 1] = e[z - 1] + e[z - 9]
            e[z - 2] = e[z - 2] + e[z - 10]
            e[z - 3] = e[z - 3] + e[z - 11]
            e[z - 8] = k00
            e[z - 9] = k11
            e[z - 10] = (l00 + l11) * a2
            e[z - 11] = (l11 - l00) * a2

            k00 = e[z - 4] - e[z - 12]
            k11 = e[z - 5] - e[z - 13]
            l00 = e[z - 6] - e[z - 14]
            l11 = e[z - 7] - e[z - 15]
            e[z - 4] = e[z - 4] + e[z - 12]
            e[z - 5] = e[z - 5] + e[z - 13]
            e[z - 6] = e[z - 6] + e[z - 14]
            e[z - 7] = e[z - 7] + e[z - 15]
            e[z - 12] = k11
            e[z - 13] = -k00
            e[z - 14] = (l11 - l00) * a2
            e[z - 15] = (l00 + l11) * -a2

            iter54(e, z)
            iter54(e, z - 8)
            z -= 16
        }
    }

    private fun inverseMdct(buffer: FloatArray, n: Int, blocktype: Int) {
        val n2 = n shr 1
        val n4 = n shr 2
        val n8 = n shr 3
        val buf2 = FloatArray(n2)
        val a = tA[blocktype]!!

        // merged: copy and reflect spectral data + step 0
        run {
            var d = n2 - 2
            var aaIdx = 0
            var eIdx = 0
            val eStop = n2
            while (eIdx != eStop) {
                buf2[d + 1] = buffer[eIdx] * a[aaIdx] - buffer[eIdx + 2] * a[aaIdx + 1]
                buf2[d] = buffer[eIdx] * a[aaIdx + 1] + buffer[eIdx + 2] * a[aaIdx]
                d -= 2
                aaIdx += 2
                eIdx += 4
            }

            var e2 = n2 - 3
            while (d >= 0) {
                buf2[d + 1] = -buffer[e2 + 2] * a[aaIdx] - -buffer[e2] * a[aaIdx + 1]
                buf2[d] = -buffer[e2 + 2] * a[aaIdx + 1] + -buffer[e2] * a[aaIdx]
                d -= 2
                aaIdx += 2
                e2 -= 4
            }
        }

        val u = buffer
        val v = buf2

        // step 2
        run {
            var aaIdx = n2 - 8
            var e0 = n4
            var e1 = 0
            var d0 = n4
            var d1 = 0

            while (aaIdx >= 0) {
                var v41_21 = v[e0 + 1] - v[e1 + 1]
                var v40_20 = v[e0] - v[e1]
                u[d0 + 1] = v[e0 + 1] + v[e1 + 1]
                u[d0] = v[e0] + v[e1]
                u[d1 + 1] = v41_21 * a[aaIdx + 4] - v40_20 * a[aaIdx + 5]
                u[d1] = v40_20 * a[aaIdx + 4] + v41_21 * a[aaIdx + 5]

                v41_21 = v[e0 + 3] - v[e1 + 3]
                v40_20 = v[e0 + 2] - v[e1 + 2]
                u[d0 + 3] = v[e0 + 3] + v[e1 + 3]
                u[d0 + 2] = v[e0 + 2] + v[e1 + 2]
                u[d1 + 3] = v41_21 * a[aaIdx] - v40_20 * a[aaIdx + 1]
                u[d1 + 2] = v40_20 * a[aaIdx] + v41_21 * a[aaIdx + 1]

                aaIdx -= 8
                d0 += 4
                d1 += 4
                e0 += 4
                e1 += 4
            }
        }

        // step 3
        val ld = ilog(n) - 1

        imdctStep3Iter0Loop(n shr 4, u, n2 - 1 - n4 * 0, -(n shr 3), a)
        imdctStep3Iter0Loop(n shr 4, u, n2 - 1 - n4 * 1, -(n shr 3), a)

        imdctStep3InnerRLoop(n shr 5, u, n2 - 1 - n8 * 0, -(n shr 4), a, 16)
        imdctStep3InnerRLoop(n shr 5, u, n2 - 1 - n8 * 1, -(n shr 4), a, 16)
        imdctStep3InnerRLoop(n shr 5, u, n2 - 1 - n8 * 2, -(n shr 4), a, 16)
        imdctStep3InnerRLoop(n shr 5, u, n2 - 1 - n8 * 3, -(n shr 4), a, 16)

        var l = 2
        while ((ld - 3) shr 1 > l) {
            val k0 = n shr (l + 2)
            val lim = 1 shl (l + 1)
            for (i in 0 until lim)
                imdctStep3InnerRLoop(n shr (l + 4), u, n2 - 1 - k0 * i, -(k0 shr 1), a, 1 shl (l + 3))
            ++l
        }

        while (ld - 6 > l) {
            val k0 = n shr (l + 2)
            val k1 = 1 shl (l + 3)
            val k0_2 = k0 shr 1
            val rlim = n shr (l + 6)
            val lim = 1 shl (l + 1)
            var a0Off = 0
            var iOff = n2 - 1
            for (r in rlim downTo 1) {
                imdctStep3InnerSLoop(lim, u, iOff, -k0_2, a, a0Off, k1, k0)
                a0Off += k1 * 4
                iOff -= 8
            }
            ++l
        }

        imdctStep3InnerSLoopLd654(n shr 5, u, n2 - 1, a, n)

        // step 4, 5, and 6
        run {
            val bitrev = bitReverseData[blocktype]!!
            var d0 = n4 - 4
            var d1 = n2 - 4
            var brIdx = 0
            while (d0 >= 0) {
                var k4 = bitrev[brIdx]
                v[d1 + 3] = u[k4]
                v[d1 + 2] = u[k4 + 1]
                v[d0 + 3] = u[k4 + 2]
                v[d0 + 2] = u[k4 + 3]

                k4 = bitrev[brIdx + 1]
                v[d1 + 1] = u[k4]
                v[d1] = u[k4 + 1]
                v[d0 + 1] = u[k4 + 2]
                v[d0] = u[k4 + 3]

                d0 -= 4
                d1 -= 4
                brIdx += 2
            }
        }

        // step 7
        run {
            val c = tC[blocktype]!!
            var cIdx = 0
            var d = 0
            var e = n2 - 4

            while (e > d) {
                var a02 = v[d] - v[e + 2]
                var a11 = v[d + 1] + v[e + 3]

                var b0 = c[cIdx + 1] * a02 + c[cIdx] * a11
                var b1 = c[cIdx + 1] * a11 - c[cIdx] * a02

                var b2 = v[d] + v[e + 2]
                var b3 = v[d + 1] - v[e + 3]

                v[d] = b2 + b0
                v[d + 1] = b3 + b1
                v[e + 2] = b2 - b0
                v[e + 3] = b1 - b3

                a02 = v[d + 2] - v[e]
                a11 = v[d + 3] + v[e + 1]

                b0 = c[cIdx + 3] * a02 + c[cIdx + 2] * a11
                b1 = c[cIdx + 3] * a11 - c[cIdx + 2] * a02

                b2 = v[d + 2] + v[e]
                b3 = v[d + 3] - v[e + 1]

                v[d + 2] = b2 + b0
                v[d + 3] = b3 + b1
                v[e] = b2 - b0
                v[e + 1] = b1 - b3

                cIdx += 4
                d += 4
                e -= 4
            }
        }

        // step 8+decode
        run {
            val bArr = tB[blocktype]!!
            var bIdx = n2 - 8
            var eIdx = n2 - 8
            var d0 = 0
            var d1 = n2 - 4
            var d2 = n2
            var d3 = n - 4
            while (eIdx >= 0) {
                var p3 = v[eIdx + 6] * bArr[bIdx + 7] - v[eIdx + 7] * bArr[bIdx + 6]
                var p2 = -v[eIdx + 6] * bArr[bIdx + 6] - v[eIdx + 7] * bArr[bIdx + 7]

                buffer[d0] = p3
                buffer[d1 + 3] = -p3
                buffer[d2] = p2
                buffer[d3 + 3] = p2

                var p1 = v[eIdx + 4] * bArr[bIdx + 5] - v[eIdx + 5] * bArr[bIdx + 4]
                var p0 = -v[eIdx + 4] * bArr[bIdx + 4] - v[eIdx + 5] * bArr[bIdx + 5]

                buffer[d0 + 1] = p1
                buffer[d1 + 2] = -p1
                buffer[d2 + 1] = p0
                buffer[d3 + 2] = p0

                p3 = v[eIdx + 2] * bArr[bIdx + 3] - v[eIdx + 3] * bArr[bIdx + 2]
                p2 = -v[eIdx + 2] * bArr[bIdx + 2] - v[eIdx + 3] * bArr[bIdx + 3]

                buffer[d0 + 2] = p3
                buffer[d1 + 1] = -p3
                buffer[d2 + 2] = p2
                buffer[d3 + 1] = p2

                p1 = v[eIdx] * bArr[bIdx + 1] - v[eIdx + 1] * bArr[bIdx]
                p0 = -v[eIdx] * bArr[bIdx] - v[eIdx + 1] * bArr[bIdx + 1]

                buffer[d0 + 3] = p1
                buffer[d1] = -p1
                buffer[d2 + 3] = p0
                buffer[d3] = p0

                bIdx -= 8
                eIdx -= 8
                d0 += 4
                d2 += 4
                d1 -= 4
                d3 -= 4
            }
        }
    }

    // overload for imdctStep3InnerSLoop that takes a0Off as int offset into the array
    private fun imdctStep3InnerSLoop(n: Int, e: FloatArray, iOff: Int, kOff: Int, aBase: FloatArray, a0Off: Int, k1: Int, k0: Int) {
        val a0 = aBase[a0Off]
        val a1 = aBase[a0Off + 1]
        val a2 = aBase[a0Off + k1]
        val a3 = aBase[a0Off + k1 + 1]
        val a4 = aBase[a0Off + k1 * 2]
        val a5 = aBase[a0Off + k1 * 2 + 1]
        val a6 = aBase[a0Off + k1 * 3]
        val a7 = aBase[a0Off + k1 * 3 + 1]

        var ee0 = iOff
        var ee2 = ee0 + kOff

        for (i in 0 until n) {
            var k00 = e[ee0] - e[ee2]
            var k11 = e[ee0 - 1] - e[ee2 - 1]
            e[ee0] = e[ee0] + e[ee2]
            e[ee0 - 1] = e[ee0 - 1] + e[ee2 - 1]
            e[ee2] = k00 * a0 - k11 * a1
            e[ee2 - 1] = k11 * a0 + k00 * a1

            k00 = e[ee0 - 2] - e[ee2 - 2]
            k11 = e[ee0 - 3] - e[ee2 - 3]
            e[ee0 - 2] = e[ee0 - 2] + e[ee2 - 2]
            e[ee0 - 3] = e[ee0 - 3] + e[ee2 - 3]
            e[ee2 - 2] = k00 * a2 - k11 * a3
            e[ee2 - 3] = k11 * a2 + k00 * a3

            k00 = e[ee0 - 4] - e[ee2 - 4]
            k11 = e[ee0 - 5] - e[ee2 - 5]
            e[ee0 - 4] = e[ee0 - 4] + e[ee2 - 4]
            e[ee0 - 5] = e[ee0 - 5] + e[ee2 - 5]
            e[ee2 - 4] = k00 * a4 - k11 * a5
            e[ee2 - 5] = k11 * a4 + k00 * a5

            k00 = e[ee0 - 6] - e[ee2 - 6]
            k11 = e[ee0 - 7] - e[ee2 - 7]
            e[ee0 - 6] = e[ee0 - 6] + e[ee2 - 6]
            e[ee0 - 7] = e[ee0 - 7] + e[ee2 - 7]
            e[ee2 - 6] = k00 * a6 - k11 * a7
            e[ee2 - 7] = k11 * a6 + k00 * a7

            ee0 -= k0
            ee2 -= k0
        }
    }

    // ====================================================================
    // Floor decode (do_floor)
    // ====================================================================

    private fun getWindow(len: Int): FloatArray? {
        val l = len shl 1
        if (l == blocksize0) return windowData[0]
        if (l == blocksize1) return windowData[1]
        return null
    }

    private fun doFloor(map: Mapping, i: Int, n: Int, target: FloatArray, finalYArr: ShortArray): Boolean {
        val n2 = n shr 1
        val s = map.chan!![i].mux
        val floor = map.submapFloor[s]
        if (floorTypes[floor] == 0) {
            return setError(STBVorbisError.INVALID_STREAM)
        } else {
            val g = floorConfig!![floor]!!
            val lx0 = 0
            var ly = (finalYArr[0].toInt() and 0xFFFF) * g.floor1Multiplier
            var lx = lx0
            for (q in 1 until g.values) {
                val j = g.sortedOrder[q]
                if (finalYArr[j].toInt() >= 0) {
                    val hy = (finalYArr[j].toInt() and 0xFFFF) * g.floor1Multiplier
                    val hx = g.xList[j].toInt() and 0xFFFF
                    if (lx != hx)
                        drawLine(target, lx, ly, hx, hy, n2)
                    lx = hx
                    ly = hy
                }
            }
            if (n2 > lx) {
                val dbVal = inverseDbTable[ly and 255]
                for (j in lx until n2)
                    target[j] *= dbVal
            }
        }
        return true
    }

    // ====================================================================
    // Vorbis decode
    // ====================================================================

    private fun vorbisDecodeInitial(pLeftStart: IntArray, pLeftEnd: IntArray, pRightStart: IntArray, pRightEnd: IntArray, mode: IntArray): Boolean {
        channelBufferStart = 0
        channelBufferEnd = 0

        while (true) {
            if (eof) return false
            if (!maybeStartPacket()) return false
            // check packet type
            if (getBits(1) != 0) {
                while (EOP != get8Packet()) { /* consume */ }
                continue // retry
            }

            val i = getBits(ilog(modeCount - 1))
            if (i == EOP) return false
            if (i >= modeCount) return false
            mode[0] = i
            val m = modeConfig[i]
            val n: Int
            val prev: Int
            val next: Int
            if (m.blockflag != 0) {
                n = blocksize1
                prev = getBits(1)
                next = getBits(1)
            } else {
                n = blocksize0
                prev = 0
                next = 0
            }

            val windowCenter = n shr 1
            if (m.blockflag != 0 && prev == 0) {
                pLeftStart[0] = (n - blocksize0) shr 2
                pLeftEnd[0] = (n + blocksize0) shr 2
            } else {
                pLeftStart[0] = 0
                pLeftEnd[0] = windowCenter
            }
            if (m.blockflag != 0 && next == 0) {
                pRightStart[0] = (n * 3 - blocksize0) shr 2
                pRightEnd[0] = (n * 3 + blocksize0) shr 2
            } else {
                pRightStart[0] = windowCenter
                pRightEnd[0] = n
            }
            return true
        }
    }

    private fun vorbisDecodePacketRest(lenArr: IntArray, m: Mode, leftStart0: Int, leftEnd: Int, rightStart: Int, rightEnd: Int, pLeft: IntArray): Boolean {
        var leftStart = leftStart0
        val n = blocksize[m.blockflag]
        val map = mappings!![m.mapping]

        val n2 = n shr 1
        val zeroChannel = BooleanArray(256)
        val reallyZeroChannel = BooleanArray(256)

        // FLOORS
        for (i in 0 until channels) {
            val s = map.chan!![i].mux
            zeroChannel[i] = false
            val floor = map.submapFloor[s]
            if (floorTypes[floor] == 0) {
                return setError(STBVorbisError.INVALID_STREAM)
            } else {
                val g = floorConfig!![floor]!!
                if (getBits(1) != 0) {
                    val rangeList = intArrayOf(256, 128, 86, 64)
                    val range = rangeList[g.floor1Multiplier - 1]
                    var offset = 2
                    val fy = finalY[i]!!
                    fy[0] = getBits(ilog(range) - 1).toShort()
                    fy[1] = getBits(ilog(range) - 1).toShort()
                    for (j in 0 until g.partitions) {
                        val pclass = g.partitionClassList[j]
                        val cdim = g.classDimensions[pclass]
                        val cbits = g.classSubclasses[pclass]
                        val csub = (1 shl cbits) - 1
                        var cval = 0
                        if (cbits != 0) {
                            val cb = codebooks!![g.classMasterbooks[pclass]]
                            cval = decode(cb)
                        }
                        for (k in 0 until cdim) {
                            val book = g.subclassBooks[pclass][cval and csub].toInt()
                            cval = cval shr cbits
                            if (book >= 0) {
                                val cb = codebooks!![book]
                                val temp = decode(cb)
                                fy[offset++] = temp.toShort()
                            } else {
                                fy[offset++] = 0
                            }
                        }
                    }
                    if (validBits == INVALID_BITS) {
                        zeroChannel[i] = true
                        continue
                    }
                    val step2Flag = BooleanArray(256)
                    step2Flag[0] = true
                    step2Flag[1] = true
                    for (j in 2 until g.values) {
                        val low = g.neighbors[j][0]
                        val high = g.neighbors[j][1]
                        val pred = predictPoint(g.xList[j].toInt() and 0xFFFF, g.xList[low].toInt() and 0xFFFF, g.xList[high].toInt() and 0xFFFF, fy[low].toInt(), fy[high].toInt())
                        val v = fy[j].toInt()
                        val highroom = range - pred
                        val lowroom = pred
                        val room = if (lowroom > highroom) highroom * 2 else lowroom * 2
                        if (v != 0) {
                            step2Flag[low] = true
                            step2Flag[high] = true
                            step2Flag[j] = true
                            if (v >= room) {
                                if (lowroom < highroom)
                                    fy[j] = (v - lowroom + pred).toShort()
                                else
                                    fy[j] = (pred - v + highroom - 1).toShort()
                            } else {
                                if (v and 1 != 0)
                                    fy[j] = (pred - ((v + 1) shr 1)).toShort()
                                else
                                    fy[j] = (pred + (v shr 1)).toShort()
                            }
                        } else {
                            step2Flag[j] = false
                            fy[j] = pred.toShort()
                        }
                    }
                    // defer final floor computation until after residue
                    for (j in 0 until g.values) {
                        if (!step2Flag[j])
                            fy[j] = -1
                    }
                } else {
                    zeroChannel[i] = true
                }
            }
        }

        // re-enable coupled channels if necessary
        for (i in 0 until channels) reallyZeroChannel[i] = zeroChannel[i]
        for (i in 0 until map.couplingSteps) {
            if (!zeroChannel[map.chan!![i].magnitude] || !zeroChannel[map.chan!![i].angle]) {
                zeroChannel[map.chan!![i].magnitude] = false
                zeroChannel[map.chan!![i].angle] = false
            }
        }

        // RESIDUE DECODE
        for (i in 0 until map.submaps) {
            val residueBuffers = arrayOfNulls<FloatArray>(STB_VORBIS_MAX_CHANNELS)
            val doNotDecode = BooleanArray(256)
            var ch = 0
            for (j in 0 until channels) {
                if (map.chan!![j].mux == i) {
                    if (zeroChannel[j]) {
                        doNotDecode[ch] = true
                        residueBuffers[ch] = null
                    } else {
                        doNotDecode[ch] = false
                        residueBuffers[ch] = channelBuffers[j]
                    }
                    ++ch
                }
            }
            val r = map.submapResidue[i]
            decodeResidue(residueBuffers, ch, n2, r, doNotDecode)
        }

        // INVERSE COUPLING
        for (i in map.couplingSteps - 1 downTo 0) {
            val mag = channelBuffers[map.chan!![i].magnitude]!!
            val ang = channelBuffers[map.chan!![i].angle]!!
            for (j in 0 until n2) {
                val m2: Float
                val a2: Float
                if (mag[j] > 0) {
                    if (ang[j] > 0) { m2 = mag[j]; a2 = mag[j] - ang[j] }
                    else { a2 = mag[j]; m2 = mag[j] + ang[j] }
                } else {
                    if (ang[j] > 0) { m2 = mag[j]; a2 = mag[j] + ang[j] }
                    else { a2 = mag[j]; m2 = mag[j] - ang[j] }
                }
                mag[j] = m2
                ang[j] = a2
            }
        }

        // finish decoding the floors (deferred)
        for (i in 0 until channels) {
            if (reallyZeroChannel[i]) {
                channelBuffers[i]!!.fill(0f, 0, n2)
            } else {
                doFloor(map, i, n, channelBuffers[i]!!, finalY[i]!!)
            }
        }

        // INVERSE MDCT
        for (i in 0 until channels)
            inverseMdct(channelBuffers[i]!!, n, m.blockflag)

        // flush remaining packet data
        flushPacket()

        if (firstDecode) {
            // intentional unsigned overflow: start of first frame is positioned for discard
            currentLoc = -n2 // wraps around for unsigned interpretation
            discardSamplesDeferred = n - rightEnd
            currentLocValid = true
            firstDecode = false
        } else if (discardSamplesDeferred != 0) {
            if (discardSamplesDeferred >= rightStart - leftStart) {
                discardSamplesDeferred -= (rightStart - leftStart)
                leftStart = rightStart
                pLeft[0] = leftStart
            } else {
                leftStart += discardSamplesDeferred
                pLeft[0] = leftStart
                discardSamplesDeferred = 0
            }
        }

        // check if we have ogg information about the sample # for this packet
        if (lastSegWhich == endSegWithKnownLoc) {
            if (currentLocValid && (pageFlag and PAGEFLAG_LAST_PAGE != 0)) {
                val currentEnd = knownLocForPacket
                if ((currentEnd.toLong() and 0xFFFFFFFFL) < ((currentLoc.toLong() and 0xFFFFFFFFL) + (rightEnd - leftStart))) {
                    if ((currentEnd.toLong() and 0xFFFFFFFFL) < (currentLoc.toLong() and 0xFFFFFFFFL)) {
                        lenArr[0] = 0
                    } else {
                        lenArr[0] = currentEnd - currentLoc
                    }
                    lenArr[0] += leftStart
                    if (rightEnd < lenArr[0]) lenArr[0] = rightEnd
                    currentLoc += lenArr[0]
                    return true
                }
            }
            currentLoc = knownLocForPacket - (n2 - leftStart)
            currentLocValid = true
        }
        if (currentLocValid)
            currentLoc += (rightStart - leftStart)

        lenArr[0] = rightEnd
        return true
    }

    private fun vorbisDecodePacket(lenArr: IntArray, pLeft: IntArray, pRight: IntArray): Boolean {
        val mode = IntArray(1)
        val leftEnd = IntArray(1)
        val rightEnd = IntArray(1)
        if (!vorbisDecodeInitial(pLeft, leftEnd, pRight, rightEnd, mode)) return false
        return vorbisDecodePacketRest(lenArr, modeConfig[mode[0]], pLeft[0], leftEnd[0], pRight[0], rightEnd[0], pLeft)
    }

    private fun vorbisFinishFrame(len0: Int, left: Int, right: Int): Int {
        var len = len0
        // mixin from previous window
        if (previousLength != 0) {
            val n = previousLength
            val w = getWindow(n) ?: return 0
            for (i in 0 until channels) {
                for (j in 0 until n) {
                    channelBuffers[i]!![left + j] =
                        channelBuffers[i]!![left + j] * w[j] +
                        previousWindow[i]!![j] * w[n - 1 - j]
                }
            }
        }

        val prev = previousLength
        previousLength = len - right

        for (i in 0 until channels) {
            var j = 0
            while (right + j < len) {
                previousWindow[i]!![j] = channelBuffers[i]!![right + j]
                ++j
            }
        }

        if (prev == 0) return 0

        if (right > len) len = len // truncate a short frame (len < right case handled)
        // Actually: if len < right, right = len
        val actualRight = if (right > len) len else right

        samplesOutput += actualRight - left
        return actualRight - left
    }

    private fun vorbisPumpFirstFrame(): Boolean {
        val lenArr = IntArray(1)
        val leftArr = IntArray(1)
        val rightArr = IntArray(1)
        val res = vorbisDecodePacket(lenArr, leftArr, rightArr)
        if (res)
            vorbisFinishFrame(lenArr[0], leftArr[0], rightArr[0])
        return res
    }

    // ====================================================================
    // Header parsing (start_decoder)
    // ====================================================================

    private fun startDecoder(): Boolean {
        val header = ByteArray(6)
        var longestFloorlist = 0

        firstDecode = true

        if (!startPage()) return false
        if (pageFlag and PAGEFLAG_FIRST_PAGE == 0) return setError(STBVorbisError.INVALID_FIRST_PAGE)
        if (pageFlag and PAGEFLAG_LAST_PAGE != 0) return setError(STBVorbisError.INVALID_FIRST_PAGE)
        if (pageFlag and PAGEFLAG_CONTINUED_PACKET != 0) return setError(STBVorbisError.INVALID_FIRST_PAGE)
        if (segmentCount != 1) return setError(STBVorbisError.INVALID_FIRST_PAGE)
        if (segments[0] != 30) {
            // check for Ogg skeleton fishead
            if (segments[0] == 64) {
                val h = ByteArray(6)
                if (getn(h, 0, 6) &&
                    h[0] == 'f'.code.toByte() && h[1] == 'i'.code.toByte() &&
                    h[2] == 's'.code.toByte() && h[3] == 'h'.code.toByte() &&
                    h[4] == 'e'.code.toByte() && h[5] == 'a'.code.toByte() &&
                    get8() == 'd'.code && get8() == 0
                ) return setError(STBVorbisError.OGG_SKELETON_NOT_SUPPORTED)
            }
            return setError(STBVorbisError.INVALID_FIRST_PAGE)
        }

        // read packet
        if (get8() != VORBIS_PACKET_ID) return setError(STBVorbisError.INVALID_FIRST_PAGE)
        if (!getn(header, 0, 6)) return setError(STBVorbisError.UNEXPECTED_EOF)
        if (!vorbisValidate(header, 0)) return setError(STBVorbisError.INVALID_FIRST_PAGE)
        if (get32() != 0) return setError(STBVorbisError.INVALID_FIRST_PAGE)
        channels = get8()
        if (channels == 0) return setError(STBVorbisError.INVALID_FIRST_PAGE)
        if (STB_VORBIS_MAX_CHANNELS < channels) return setError(STBVorbisError.TOO_MANY_CHANNELS)
        sampleRate = get32()
        if (sampleRate == 0) return setError(STBVorbisError.INVALID_FIRST_PAGE)
        get32() // bitrate_maximum
        get32() // bitrate_nominal
        get32() // bitrate_minimum
        val x = get8()
        val log0 = x and 15
        val log1 = x shr 4
        blocksize0 = 1 shl log0
        blocksize1 = 1 shl log1
        if (6 > log0 || 13 < log0) return setError(STBVorbisError.INVALID_SETUP)
        if (6 > log1 || 13 < log1) return setError(STBVorbisError.INVALID_SETUP)
        if (log0 > log1) return setError(STBVorbisError.INVALID_SETUP)

        // framing_flag
        val ff = get8()
        if (ff and 1 == 0) return setError(STBVorbisError.INVALID_FIRST_PAGE)

        // second packet
        if (!startPage()) return false
        if (!startPacket()) return false
        if (nextSegment() == 0) return false

        if (get8Packet() != VORBIS_PACKET_COMMENT) return setError(STBVorbisError.INVALID_SETUP)
        val header2 = IntArray(6)
        for (i in 0 until 6) header2[i] = get8Packet()
        if (!vorbisValidateInts(header2, 0)) return setError(STBVorbisError.INVALID_SETUP)

        // vendor string
        val vendorLen = get32Packet()
        // skip vendor
        for (i in 0 until vendorLen) get8Packet()

        // user comments
        val commentCount = get32Packet()
        for (i in 0 until commentCount) {
            val commentLen = get32Packet()
            for (j in 0 until commentLen) get8Packet()
        }

        // framing_flag
        val ff2 = get8Packet()
        if (ff2 and 1 == 0) return setError(STBVorbisError.INVALID_SETUP)

        skip(bytesInSeg)
        bytesInSeg = 0

        var segLen: Int
        do {
            segLen = nextSegment()
            skip(segLen)
            bytesInSeg = 0
        } while (segLen != 0)

        // third packet
        if (!startPacket()) return false

        crc32Init()

        if (get8Packet() != VORBIS_PACKET_SETUP) return setError(STBVorbisError.INVALID_SETUP)
        val header3 = IntArray(6)
        for (i in 0 until 6) header3[i] = get8Packet()
        if (!vorbisValidateInts(header3, 0)) return setError(STBVorbisError.INVALID_SETUP)

        // ---- Codebooks ----
        codebookCount = getBits(8) + 1
        codebooks = Array(codebookCount) { Codebook() }
        for (i in 0 until codebookCount) {
            val c = codebooks!![i]
            if (getBits(8) != 0x42) return setError(STBVorbisError.INVALID_SETUP)
            if (getBits(8) != 0x43) return setError(STBVorbisError.INVALID_SETUP)
            if (getBits(8) != 0x56) return setError(STBVorbisError.INVALID_SETUP)
            var xb = getBits(8)
            c.dimensions = (getBits(8) shl 8) + xb
            xb = getBits(8)
            val yb = getBits(8)
            c.entries = (getBits(8) shl 16) + (yb shl 8) + xb
            val ordered = getBits(1)
            c.sparse = if (ordered != 0) 0 else getBits(1)

            if (c.dimensions == 0 && c.entries != 0) return setError(STBVorbisError.INVALID_SETUP)

            val lengths: ByteArray
            if (c.sparse != 0) {
                lengths = ByteArray(c.entries)
            } else {
                lengths = ByteArray(c.entries)
                c.codewordLengths = lengths
            }

            var total = 0
            if (ordered != 0) {
                var currentEntry = 0
                var currentLength = getBits(5) + 1
                while (currentEntry < c.entries) {
                    val limit = c.entries - currentEntry
                    val n = getBits(ilog(limit))
                    if (currentLength >= 32) return setError(STBVorbisError.INVALID_SETUP)
                    if (currentEntry + n > c.entries) return setError(STBVorbisError.INVALID_SETUP)
                    for (idx in currentEntry until currentEntry + n) {
                        lengths[idx] = currentLength.toByte()
                    }
                    currentEntry += n
                    ++currentLength
                }
            } else {
                for (j in 0 until c.entries) {
                    val present = if (c.sparse != 0) getBits(1) else 1
                    if (present != 0) {
                        lengths[j] = (getBits(5) + 1).toByte()
                        ++total
                        if ((lengths[j].toInt() and 0xFF) == 32)
                            return setError(STBVorbisError.INVALID_SETUP)
                    } else {
                        lengths[j] = NO_CODE.toByte()
                    }
                }
            }

            if (c.sparse != 0 && total >= c.entries shr 2) {
                // convert sparse to non-sparse
                c.codewordLengths = lengths.copyOf()
                c.sparse = 0
            }

            // compute the size of sorted tables
            val sortedCount: Int
            if (c.sparse != 0) {
                sortedCount = total
            } else {
                var sc = 0
                for (j in 0 until c.entries)
                    if ((lengths[j].toInt() and 0xFF) > STB_VORBIS_FAST_HUFFMAN_LENGTH && (lengths[j].toInt() and 0xFF) != NO_CODE)
                        ++sc
                sortedCount = sc
            }

            c.sortedEntries = sortedCount
            var values: IntArray? = null

            if (c.sparse == 0) {
                c.codewords = IntArray(c.entries)
            } else {
                if (c.sortedEntries != 0) {
                    c.codewordLengths = ByteArray(c.sortedEntries)
                    c.codewords = IntArray(c.sortedEntries)
                    values = IntArray(c.sortedEntries)
                }
            }

            val finalLengths = if (c.sparse != 0) lengths else c.codewordLengths!!
            if (!computeCodewords(c, finalLengths, c.entries, values)) {
                return setError(STBVorbisError.INVALID_SETUP)
            }

            if (c.sortedEntries != 0) {
                c.sortedCodewords = IntArray(c.sortedEntries + 1)
                c.sortedValues = IntArray(c.sortedEntries)
                computeSortedHuffman(c, finalLengths, values)
            }

            if (c.sparse != 0) {
                c.codewords = null
            }

            computeAcceleratedHuffman(c)

            c.lookupType = getBits(4)
            if (2 < c.lookupType) return setError(STBVorbisError.INVALID_SETUP)
            if (0 < c.lookupType) {
                c.minimumValue = float32Unpack(getBits(32))
                c.deltaValue = float32Unpack(getBits(32))
                c.valueBits = getBits(4) + 1
                c.sequenceP = getBits(1)
                if (c.lookupType == 1) {
                    val lv = lookup1Values(c.entries, c.dimensions)
                    if (0 > lv) return setError(STBVorbisError.INVALID_SETUP)
                    c.lookupValues = lv
                } else {
                    c.lookupValues = c.entries * c.dimensions
                }
                if (c.lookupValues == 0) return setError(STBVorbisError.INVALID_SETUP)
                val mults = IntArray(c.lookupValues)
                for (j in 0 until c.lookupValues) {
                    val q = getBits(c.valueBits)
                    if (q == EOP) return setError(STBVorbisError.INVALID_SETUP)
                    mults[j] = q
                }

                // non-divides-in-codebook path: pre-expand lookup1
                if (c.lookupType == 1) {
                    var last = 0f
                    val isSparse = c.sparse != 0
                    if (isSparse && c.sortedEntries == 0) {
                        // skip - no entries to expand
                    } else {
                        val expandLen = if (isSparse) c.sortedEntries else c.entries
                        c.multiplicands = FloatArray(expandLen * c.dimensions)
                        for (j in 0 until expandLen) {
                            val z = if (isSparse) c.sortedValues!![j] else j
                            var div = 1
                            for (k in 0 until c.dimensions) {
                                val off = (z / div) % c.lookupValues
                                val v = mults[off] * c.deltaValue + c.minimumValue + last
                                c.multiplicands!![j * c.dimensions + k] = v
                                if (c.sequenceP != 0) last = v
                                if (k + 1 < c.dimensions) {
                                    if ((div.toLong() and 0xFFFFFFFFL) > (0xFFFFFFFFL / (c.lookupValues.toLong() and 0xFFFFFFFFL))) {
                                        return setError(STBVorbisError.INVALID_SETUP)
                                    }
                                    div *= c.lookupValues
                                }
                            }
                        }
                        c.lookupType = 2
                    }
                } else {
                    // lookup_type == 2
                    var last = 0f
                    c.multiplicands = FloatArray(c.lookupValues)
                    for (j in 0 until c.lookupValues) {
                        val v = mults[j] * c.deltaValue + c.minimumValue + last
                        c.multiplicands!![j] = v
                        if (c.sequenceP != 0) last = v
                    }
                }
            }
        }

        // time domain transfers (not used)
        val tdCount = getBits(6) + 1
        for (i in 0 until tdCount) {
            val z = getBits(16)
            if (z != 0) return setError(STBVorbisError.INVALID_SETUP)
        }

        // ---- Floors ----
        floorCount = getBits(6) + 1
        floorConfig = arrayOfNulls(floorCount)
        for (i in 0 until floorCount) {
            floorTypes[i] = getBits(16)
            if (1 < floorTypes[i]) return setError(STBVorbisError.INVALID_SETUP)
            if (floorTypes[i] == 0) {
                // floor 0 not supported
                // read the fields anyway then error
                getBits(8) // order
                getBits(16) // rate
                getBits(16) // bark_map_size
                getBits(6) // amplitude_bits
                getBits(8) // amplitude_offset
                val nBooks = getBits(4) + 1
                for (j in 0 until nBooks) getBits(8)
                return setError(STBVorbisError.FEATURE_NOT_SUPPORTED)
            } else {
                val g = Floor1()
                floorConfig!![i] = g
                var maxClass = -1
                g.partitions = getBits(5)
                for (j in 0 until g.partitions) {
                    g.partitionClassList[j] = getBits(4)
                    if (g.partitionClassList[j] > maxClass)
                        maxClass = g.partitionClassList[j]
                }
                for (j in 0..maxClass) {
                    g.classDimensions[j] = getBits(3) + 1
                    g.classSubclasses[j] = getBits(2)
                    if (g.classSubclasses[j] != 0) {
                        g.classMasterbooks[j] = getBits(8)
                        if (g.classMasterbooks[j] >= codebookCount) return setError(STBVorbisError.INVALID_SETUP)
                    }
                    for (k in 0 until (1 shl g.classSubclasses[j])) {
                        g.subclassBooks[j][k] = (getBits(8) - 1).toShort()
                        if (g.subclassBooks[j][k].toInt() >= codebookCount) return setError(STBVorbisError.INVALID_SETUP)
                    }
                }
                g.floor1Multiplier = getBits(2) + 1
                g.rangebits = getBits(4)
                g.xList[0] = 0
                g.xList[1] = (1 shl g.rangebits).toShort()
                g.values = 2
                for (j in 0 until g.partitions) {
                    val c = g.partitionClassList[j]
                    for (k in 0 until g.classDimensions[c]) {
                        g.xList[g.values] = getBits(g.rangebits).toShort()
                        ++g.values
                    }
                }
                // precompute sorting
                data class FloorOrdering(val x: Int, val id: Int)
                val p = Array(g.values) { FloorOrdering(g.xList[it].toInt() and 0xFFFF, it) }
                val sorted = p.sortedBy { it.x }
                for (j in 0 until g.values - 1)
                    if (sorted[j].x == sorted[j + 1].x)
                        return setError(STBVorbisError.INVALID_SETUP)
                for (j in 0 until g.values)
                    g.sortedOrder[j] = sorted[j].id
                // precompute neighbors
                for (j in 2 until g.values) {
                    val lowArr = IntArray(1)
                    val highArr = IntArray(1)
                    findNeighbors(g.xList, j, lowArr, highArr)
                    g.neighbors[j][0] = lowArr[0]
                    g.neighbors[j][1] = highArr[0]
                }
                if (g.values > longestFloorlist)
                    longestFloorlist = g.values
            }
        }

        // ---- Residues ----
        residueCount = getBits(6) + 1
        residueConfig = Array(residueCount) { Residue() }
        for (i in 0 until residueCount) {
            val r = residueConfig!![i]
            residueTypes[i] = getBits(16)
            if (2 < residueTypes[i]) return setError(STBVorbisError.INVALID_SETUP)
            r.begin = getBits(24)
            r.end = getBits(24)
            if (r.begin > r.end) return setError(STBVorbisError.INVALID_SETUP)
            r.partSize = getBits(24) + 1
            r.classifications = getBits(6) + 1
            r.classbook = getBits(8)
            if (r.classbook >= codebookCount) return setError(STBVorbisError.INVALID_SETUP)
            val residueCascade = IntArray(64)
            for (j in 0 until r.classifications) {
                val lowBits = getBits(3)
                val highBits = if (getBits(1) != 0) getBits(5) else 0
                residueCascade[j] = highBits * 8 + lowBits
            }
            r.residueBooks = Array(r.classifications) { ShortArray(8) }
            for (j in 0 until r.classifications) {
                for (k in 0 until 8) {
                    if (residueCascade[j] and (1 shl k) != 0) {
                        r.residueBooks!![j][k] = getBits(8).toShort()
                        if (r.residueBooks!![j][k].toInt() >= codebookCount) return setError(STBVorbisError.INVALID_SETUP)
                    } else {
                        r.residueBooks!![j][k] = -1
                    }
                }
            }
            // precompute classdata
            val cbEntries = codebooks!![r.classbook].entries
            r.classdata = arrayOfNulls(cbEntries)
            for (j in 0 until cbEntries) {
                val classwordsCount = codebooks!![r.classbook].dimensions
                var temp = j
                r.classdata!![j] = ByteArray(classwordsCount)
                for (k in classwordsCount - 1 downTo 0) {
                    r.classdata!![j]!![k] = (temp % r.classifications).toByte()
                    temp /= r.classifications
                }
            }
        }

        // ---- Mappings ----
        mappingCount = getBits(6) + 1
        mappings = Array(mappingCount) { Mapping() }
        for (i in 0 until mappingCount) {
            val map = mappings!![i]
            val mappingType = getBits(16)
            if (mappingType != 0) return setError(STBVorbisError.INVALID_SETUP)
            map.chan = Array(channels) { MappingChannel() }
            if (getBits(1) != 0)
                map.submaps = getBits(4) + 1
            else
                map.submaps = 1
            if (getBits(1) != 0) {
                map.couplingSteps = getBits(8) + 1
                if (map.couplingSteps > channels) return setError(STBVorbisError.INVALID_SETUP)
                for (k in 0 until map.couplingSteps) {
                    map.chan!![k].magnitude = getBits(ilog(channels - 1))
                    map.chan!![k].angle = getBits(ilog(channels - 1))
                    if (map.chan!![k].magnitude >= channels) return setError(STBVorbisError.INVALID_SETUP)
                    if (map.chan!![k].angle >= channels) return setError(STBVorbisError.INVALID_SETUP)
                    if (map.chan!![k].magnitude == map.chan!![k].angle) return setError(STBVorbisError.INVALID_SETUP)
                }
            } else {
                map.couplingSteps = 0
            }
            if (getBits(2) != 0) return setError(STBVorbisError.INVALID_SETUP)
            if (1 < map.submaps) {
                for (j in 0 until channels) {
                    map.chan!![j].mux = getBits(4)
                    if (map.chan!![j].mux >= map.submaps) return setError(STBVorbisError.INVALID_SETUP)
                }
            } else {
                for (j in 0 until channels)
                    map.chan!![j].mux = 0
            }
            for (j in 0 until map.submaps) {
                getBits(8) // discard
                map.submapFloor[j] = getBits(8)
                map.submapResidue[j] = getBits(8)
                if (map.submapFloor[j] >= floorCount) return setError(STBVorbisError.INVALID_SETUP)
                if (map.submapResidue[j] >= residueCount) return setError(STBVorbisError.INVALID_SETUP)
            }
        }

        // ---- Modes ----
        modeCount = getBits(6) + 1
        for (i in 0 until modeCount) {
            val m = modeConfig[i]
            m.blockflag = getBits(1)
            m.windowtype = getBits(16)
            m.transformtype = getBits(16)
            m.mapping = getBits(8)
            if (m.windowtype != 0) return setError(STBVorbisError.INVALID_SETUP)
            if (m.transformtype != 0) return setError(STBVorbisError.INVALID_SETUP)
            if (m.mapping >= mappingCount) return setError(STBVorbisError.INVALID_SETUP)
        }

        flushPacket()

        previousLength = 0

        for (i in 0 until channels) {
            channelBuffers[i] = FloatArray(blocksize1)
            previousWindow[i] = FloatArray(blocksize1 / 2)
            finalY[i] = ShortArray(longestFloorlist)
        }

        if (!initBlocksize(0, blocksize0)) return false
        if (!initBlocksize(1, blocksize1)) return false
        blocksize[0] = blocksize0
        blocksize[1] = blocksize1

        if (nextSeg == -1) {
            firstAudioPageOffset = getFileOffset()
        } else {
            firstAudioPageOffset = 0
        }

        return true
    }

    // ====================================================================
    // Seeking
    // ====================================================================

    private fun vorbisFindPage(endArr: IntArray?, lastArr: IntArray?): Boolean {
        while (true) {
            if (eof) return false
            val n = get8()
            if (n == 0x4f) { // page header candidate
                val retryLoc = getFileOffset()
                if ((retryLoc.toLong() and 0xFFFFFFFFL) - 25 > (streamLen.toLong() and 0xFFFFFFFFL))
                    return false
                var matched = true
                for (i in 1 until 4) {
                    if (get8() != (oggPageHeader[i].toInt() and 0xFF)) { matched = false; break }
                }
                if (eof) return false
                if (matched) {
                    val headerBuf = ByteArray(27)
                    headerBuf[0] = 0x4f
                    headerBuf[1] = 0x67
                    headerBuf[2] = 0x67
                    headerBuf[3] = 0x53
                    for (i in 4 until 27)
                        headerBuf[i] = get8().toByte()
                    if (eof) return false
                    if (headerBuf[4].toInt() != 0) {
                        setFileOffset(retryLoc)
                        continue
                    }
                    val goal = (headerBuf[22].toInt() and 0xFF) +
                               ((headerBuf[23].toInt() and 0xFF) shl 8) +
                               ((headerBuf[24].toInt() and 0xFF) shl 16) +
                               ((headerBuf[25].toInt() and 0xFF) shl 24)
                    for (i in 22 until 26) headerBuf[i] = 0
                    var crc = 0
                    for (i in 0 until 27)
                        crc = crc32Update(crc, headerBuf[i].toInt() and 0xFF)
                    var len = 0
                    val nSegments = headerBuf[26].toInt() and 0xFF
                    for (i in 0 until nSegments) {
                        val s = get8()
                        crc = crc32Update(crc, s)
                        len += s
                    }
                    if (len != 0 && eof) return false
                    for (i in 0 until len)
                        crc = crc32Update(crc, get8())
                    if (crc == goal) {
                        if (endArr != null)
                            endArr[0] = getFileOffset()
                        if (lastArr != null) {
                            lastArr[0] = if ((headerBuf[5].toInt() and 0xFF) and 0x04 != 0) 1 else 0
                        }
                        setFileOffset(retryLoc - 1)
                        return true
                    }
                }
                setFileOffset(retryLoc)
            }
        }
    }

    private fun getSeekPageInfo(z: ProbedPage): Boolean {
        z.pageStart = getFileOffset()
        val headerBuf = ByteArray(27)
        if (!getn(headerBuf, 0, 27)) return false
        if (headerBuf[0] != 'O'.code.toByte() || headerBuf[1] != 'g'.code.toByte() ||
            headerBuf[2] != 'g'.code.toByte() || headerBuf[3] != 'S'.code.toByte())
            return false
        val nSegments = headerBuf[26].toInt() and 0xFF
        val lacing = ByteArray(nSegments)
        if (!getn(lacing, 0, nSegments)) return false
        var len = 0
        for (i in 0 until nSegments)
            len += lacing[i].toInt() and 0xFF
        z.pageEnd = z.pageStart + 27 + nSegments + len
        z.lastDecodedSample = (headerBuf[6].toInt() and 0xFF) +
                              ((headerBuf[7].toInt() and 0xFF) shl 8) +
                              ((headerBuf[8].toInt() and 0xFF) shl 16) +
                              ((headerBuf[9].toInt() and 0xFF) shl 24)
        setFileOffset(z.pageStart)
        return true
    }

    private fun goToPageBefore(limitOffset: Int): Boolean {
        val previousSafe: Int
        if (limitOffset >= 65536 && limitOffset - 65536 >= firstAudioPageOffset)
            previousSafe = limitOffset - 65536
        else
            previousSafe = firstAudioPageOffset

        setFileOffset(previousSafe)

        val endArr = IntArray(1)
        while (vorbisFindPage(endArr, null)) {
            if (endArr[0] >= limitOffset && getFileOffset() < limitOffset)
                return true
            setFileOffset(endArr[0])
        }
        return false
    }

    private fun seekToSampleCoarse(sampleNumber: Int): Boolean {
        val left = ProbedPage()
        val right = ProbedPage()
        val mid = ProbedPage()

        val streamLength = streamLengthInSamples()
        if (streamLength == 0) return setError(STBVorbisError.SEEK_WITHOUT_LENGTH)
        if ((sampleNumber.toLong() and 0xFFFFFFFFL) > (streamLength.toLong() and 0xFFFFFFFFL)) return setError(STBVorbisError.SEEK_INVALID)

        val padding = (blocksize1 - blocksize0) shr 2
        val lastSampleLimit: Int
        if ((sampleNumber.toLong() and 0xFFFFFFFFL) < padding.toLong())
            lastSampleLimit = 0
        else
            lastSampleLimit = sampleNumber - padding

        // copy pFirst
        left.pageStart = pFirst.pageStart
        left.pageEnd = pFirst.pageEnd
        left.lastDecodedSample = pFirst.lastDecodedSample

        while (left.lastDecodedSample == -1) {
            setFileOffset(left.pageEnd)
            if (!getSeekPageInfo(left)) { seekStart(); return setError(STBVorbisError.SEEK_FAILED) }
        }

        right.pageStart = pLast.pageStart
        right.pageEnd = pLast.pageEnd
        right.lastDecodedSample = pLast.lastDecodedSample

        if ((lastSampleLimit.toLong() and 0xFFFFFFFFL) <= (left.lastDecodedSample.toLong() and 0xFFFFFFFFL)) {
            if (seekStart()) {
                if ((currentLoc.toLong() and 0xFFFFFFFFL) > (sampleNumber.toLong() and 0xFFFFFFFFL))
                    return setError(STBVorbisError.SEEK_FAILED)
                return true
            }
            return false
        }

        var probe = 0
        var bytesPerSample = 0.0
        var offset = 0.0

        while (left.pageEnd != right.pageStart) {
            val delta = right.pageStart - left.pageEnd
            if (65536 >= delta) {
                setFileOffset(left.pageEnd)
            } else {
                if (2 > probe) {
                    if (probe == 0) {
                        val dataBytes = (right.pageEnd - left.pageStart).toDouble()
                        bytesPerSample = dataBytes / (right.lastDecodedSample.toLong() and 0xFFFFFFFFL).toDouble()
                        offset = left.pageStart + bytesPerSample * ((lastSampleLimit.toLong() and 0xFFFFFFFFL) - (left.lastDecodedSample.toLong() and 0xFFFFFFFFL))
                    } else {
                        var err = ((lastSampleLimit.toLong() and 0xFFFFFFFFL) - (mid.lastDecodedSample.toLong() and 0xFFFFFFFFL)).toDouble() * bytesPerSample
                        if (err >= 0 && 8000.0 > err) err = 8000.0
                        if (0.0 > err && err > -8000.0) err = -8000.0
                        offset += err * 2
                    }
                    if (left.pageEnd.toDouble() > offset) offset = left.pageEnd.toDouble()
                    if (offset > (right.pageStart - 65536).toDouble()) offset = (right.pageStart - 65536).toDouble()
                    setFileOffset(offset.toInt())
                } else {
                    setFileOffset(left.pageEnd + (delta / 2) - 32768)
                }
                if (!vorbisFindPage(null, null)) { seekStart(); return setError(STBVorbisError.SEEK_FAILED) }
            }

            while (true) {
                if (!getSeekPageInfo(mid)) { seekStart(); return setError(STBVorbisError.SEEK_FAILED) }
                if (mid.lastDecodedSample != -1) break
                setFileOffset(mid.pageEnd)
            }

            if (mid.pageStart == right.pageStart) {
                if (probe >= 2 || 65536 >= delta) break
            } else {
                if ((lastSampleLimit.toLong() and 0xFFFFFFFFL) < (mid.lastDecodedSample.toLong() and 0xFFFFFFFFL))
                    right.also { it.pageStart = mid.pageStart; it.pageEnd = mid.pageEnd; it.lastDecodedSample = mid.lastDecodedSample }
                else
                    left.also { it.pageStart = mid.pageStart; it.pageEnd = mid.pageEnd; it.lastDecodedSample = mid.lastDecodedSample }
            }
            ++probe
        }

        // seek back to start of the last packet
        val pageStart = left.pageStart
        setFileOffset(pageStart)
        if (!startPage()) return setError(STBVorbisError.SEEK_FAILED)
        var endPos = endSegWithKnownLoc

        while (true) {
            var i = endPos
            while (i > 0) {
                if (segments[i - 1] != 255) break
                --i
            }

            val startSegWithKnownLoc = i
            if (startSegWithKnownLoc > 0 || (pageFlag and PAGEFLAG_CONTINUED_PACKET == 0))
                break

            if (!goToPageBefore(pageStart)) { seekStart(); return setError(STBVorbisError.SEEK_FAILED) }

            val ps = getFileOffset()
            if (!startPage()) { seekStart(); return setError(STBVorbisError.SEEK_FAILED) }
            endPos = segmentCount - 1
        }

        // prepare to start decoding
        currentLocValid = false
        lastSeg = false
        validBits = 0
        packetBytes = 0
        bytesInSeg = 0
        previousLength = 0

        var startSegWithKnownLoc2 = endPos
        run {
            var i = endPos
            while (i > 0) {
                if (segments[i - 1] != 255) break
                --i
            }
            startSegWithKnownLoc2 = i
        }

        nextSeg = startSegWithKnownLoc2

        for (i in 0 until startSegWithKnownLoc2)
            skip(segments[i])

        if (!vorbisPumpFirstFrame()) return false
        if ((currentLoc.toLong() and 0xFFFFFFFFL) > (sampleNumber.toLong() and 0xFFFFFFFFL))
            return setError(STBVorbisError.SEEK_FAILED)
        return true
    }

    private fun peekDecodeInitial(pLeftStart: IntArray, pLeftEnd: IntArray, pRightStart: IntArray, pRightEnd: IntArray, mode: IntArray): Boolean {
        if (!vorbisDecodeInitial(pLeftStart, pLeftEnd, pRightStart, pRightEnd, mode))
            return false

        var bitsRead = 1 + ilog(modeCount - 1)
        if (modeConfig[mode[0]].blockflag != 0)
            bitsRead += 2
        val bytesRead = (bitsRead + 7) / 8

        bytesInSeg += bytesRead
        packetBytes -= bytesRead
        skip(-bytesRead)
        if (nextSeg == -1)
            nextSeg = segmentCount - 1
        else
            nextSeg--
        validBits = 0
        return true
    }

    // ====================================================================
    // Public API
    // ====================================================================

    fun getInfo(): StbVorbisInfo {
        return StbVorbisInfo(
            sampleRate = sampleRate,
            channels = channels,
            maxFrameSize = blocksize1 shr 1
        )
    }

    fun getError(): STBVorbisError {
        val e = lastError
        lastError = STBVorbisError.NO_ERROR
        return e
    }

    fun getSampleOffset(): Int {
        return if (currentLocValid) currentLoc else -1
    }

    /**
     * Decode the next frame and return the number of samples per channel.
     * The returned array contains one FloatArray per channel with samples starting at the returned offset.
     */
    fun getFrameFloat(): Triple<Int, Int, Array<FloatArray>>? {
        val lenArr = IntArray(1)
        val leftArr = IntArray(1)
        val rightArr = IntArray(1)

        if (!vorbisDecodePacket(lenArr, leftArr, rightArr)) {
            channelBufferStart = 0
            channelBufferEnd = 0
            return null
        }

        val len = vorbisFinishFrame(lenArr[0], leftArr[0], rightArr[0])
        val result = Array(channels) { channelBuffers[it]!! }
        val offset = leftArr[0]

        channelBufferStart = leftArr[0]
        channelBufferEnd = leftArr[0] + len

        return Triple(len, offset, result)
    }

    /**
     * Get interleaved float samples. Returns number of samples stored per channel.
     */
    fun getSamplesFloatInterleaved(requestedChannels: Int, buffer: FloatArray, numFloats: Int): Int {
        val len = numFloats / requestedChannels
        var n = 0
        val z = if (requestedChannels < channels) requestedChannels else channels
        while (len > n) {
            var k = channelBufferEnd - channelBufferStart
            if (n + k >= len) k = len - n
            for (j in 0 until k) {
                for (i in 0 until z)
                    buffer[(n + j) * requestedChannels + i] = channelBuffers[i]!![channelBufferStart + j]
                for (i in z until requestedChannels)
                    buffer[(n + j) * requestedChannels + i] = 0f
            }
            n += k
            channelBufferStart += k
            if (n == len) break
            if (getFrameFloat() == null) break
        }
        return n
    }

    /**
     * Get float samples into separate channel buffers. Returns number of samples stored per channel.
     */
    fun getSamplesFloat(requestedChannels: Int, buffers: Array<FloatArray>, bufferOffset: Int, numSamples: Int): Int {
        var n = 0
        val z = if (requestedChannels < channels) requestedChannels else channels
        while (numSamples > n) {
            var k = channelBufferEnd - channelBufferStart
            if (n + k >= numSamples) k = numSamples - n
            if (0 < k) {
                for (i in 0 until z)
                    channelBuffers[i]!!.copyInto(buffers[i], bufferOffset + n, channelBufferStart, channelBufferStart + k)
                for (i in z until requestedChannels)
                    buffers[i].fill(0f, bufferOffset + n, bufferOffset + n + k)
            }
            n += k
            channelBufferStart += k
            if (n == numSamples) break
            if (getFrameFloat() == null) break
        }
        return n
    }

    /**
     * Seek to approximate sample number. After calling, the next getFrameFloat will include this sample.
     */
    fun seekFrame(sampleNumber: Int): Boolean {
        if (!seekToSampleCoarse(sampleNumber)) return false

        val maxFrameSamples = (blocksize1 * 3 - blocksize0) shr 2
        while ((currentLoc.toLong() and 0xFFFFFFFFL) < (sampleNumber.toLong() and 0xFFFFFFFFL)) {
            val leftStart = IntArray(1)
            val leftEnd = IntArray(1)
            val rightStart = IntArray(1)
            val rightEnd = IntArray(1)
            val mode = IntArray(1)
            if (!peekDecodeInitial(leftStart, leftEnd, rightStart, rightEnd, mode))
                return setError(STBVorbisError.SEEK_FAILED)
            val frameSamples = rightStart[0] - leftStart[0]
            if ((currentLoc + frameSamples).toLong() and 0xFFFFFFFFL > (sampleNumber.toLong() and 0xFFFFFFFFL)) {
                return true
            } else if ((currentLoc + frameSamples + maxFrameSamples).toLong() and 0xFFFFFFFFL > (sampleNumber.toLong() and 0xFFFFFFFFL)) {
                vorbisPumpFirstFrame()
            } else {
                currentLoc += frameSamples
                previousLength = 0
                maybeStartPacket()
                flushPacket()
            }
        }
        if (currentLoc != sampleNumber) return setError(STBVorbisError.SEEK_FAILED)
        return true
    }

    /**
     * Seek to exact sample. After calling, the next getSamplesFloat will start with this sample.
     */
    fun seek(sampleNumber: Int): Boolean {
        if (!seekFrame(sampleNumber)) return false

        if (sampleNumber != currentLoc) {
            val frameStart = currentLoc
            getFrameFloat()
            channelBufferStart += (sampleNumber - frameStart)
        }
        return true
    }

    /**
     * Seek to the beginning of the stream.
     */
    fun seekStart(): Boolean {
        setFileOffset(firstAudioPageOffset)
        previousLength = 0
        firstDecode = true
        nextSeg = -1
        return vorbisPumpFirstFrame()
    }

    /**
     * Return total number of samples in the stream, or 0 if unknown.
     */
    fun streamLengthInSamples(): Int {
        if (totalSamples == 0) {
            val restoreOffset = getFileOffset()

            val previousSafe: Int
            if (streamLen >= 65536 && streamLen - 65536 >= firstAudioPageOffset)
                previousSafe = streamLen - 65536
            else
                previousSafe = firstAudioPageOffset

            setFileOffset(previousSafe)

            val endArr = IntArray(1)
            val lastArr = IntArray(1)

            if (!vorbisFindPage(endArr, lastArr)) {
                lastError = STBVorbisError.CANT_FIND_LAST_PAGE
                totalSamples = -1 // 0xffffffff
                setFileOffset(restoreOffset)
                return 0
            }

            var lastPageLoc = getFileOffset()

            while (lastArr[0] == 0) {
                setFileOffset(endArr[0])
                if (!vorbisFindPage(endArr, lastArr)) {
                    break
                }
                lastPageLoc = getFileOffset()
            }

            setFileOffset(lastPageLoc)

            // parse the header - skip 6 bytes
            val headerSkip = ByteArray(6)
            getn(headerSkip, 0, 6)
            val lo = get32()
            val hi = get32()
            if (lo == -1 && hi == -1) {
                lastError = STBVorbisError.CANT_FIND_LAST_PAGE
                totalSamples = -1
                setFileOffset(restoreOffset)
                return 0
            }
            if (hi != 0) {
                totalSamples = -2 // 0xfffffffe - saturate
            } else {
                totalSamples = lo
            }

            pLast.pageStart = lastPageLoc
            pLast.pageEnd = endArr[0]
            pLast.lastDecodedSample = lo

            setFileOffset(restoreOffset)
        }
        return if (totalSamples == -1) 0 else totalSamples
    }

    /**
     * Return total length in seconds, or 0 if unknown.
     */
    fun streamLengthInSeconds(): Float {
        return streamLengthInSamples().toFloat() / sampleRate.toFloat()
    }

    /**
     * Close/release the decoder. In Kotlin this is mostly a no-op since GC handles memory,
     * but it clears internal references.
     */
    fun close() {
        codebooks = null
        floorConfig = null
        residueConfig = null
        mappings = null
        for (i in 0 until STB_VORBIS_MAX_CHANNELS) {
            channelBuffers[i] = null
            previousWindow[i] = null
            finalY[i] = null
            outputs[i] = null
        }
        for (i in 0 until 2) {
            tA[i] = null
            tB[i] = null
            tC[i] = null
            windowData[i] = null
            bitReverseData[i] = null
        }
    }
}

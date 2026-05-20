package com.mrpowergamerbr.butterscotchpreprocessor

import kotlin.math.sqrt

// Butterscotch data.win port to C

// ===[ Parser Options ]===
data class DataWinParserOptions(
    val parseGen8: Boolean = true,
    val parseOptn: Boolean = true,
    val parseLang: Boolean = true,
    val parseExtn: Boolean = true,
    val parseSond: Boolean = true,
    val parseAgrp: Boolean = true,
    val parseSprt: Boolean = true,
    val parseBgnd: Boolean = true,
    val parsePath: Boolean = true,
    val parseScpt: Boolean = true,
    val parseGlob: Boolean = true,
    val parseShdr: Boolean = true,
    val parseFont: Boolean = true,
    val parseTmln: Boolean = true,
    val parseObjt: Boolean = true,
    val parseRoom: Boolean = true,
    val parseTpag: Boolean = true,
    val parseCode: Boolean = true,
    val parseVari: Boolean = true,
    val parseFunc: Boolean = true,
    val parseStrg: Boolean = true,
    val parseTxtr: Boolean = true,
    val parseAudo: Boolean = true,
    val skipLoadingPreciseMasksForNonPreciseSprites: Boolean = false
)

// ===[ GEN8 ]===
class Gen8 {
    var isDebuggerDisabled = 0
    var bytecodeVersion = 0
    var fileName: String? = null
    var config: String? = null
    var lastObj = 0
    var lastTile = 0
    var gameID = 0
    var directPlayGuid = ByteArray(16)
    var name: String? = null
    var major = 0
    var minor = 0
    var release = 0
    var build = 0
    var defaultWindowWidth = 0
    var defaultWindowHeight = 0
    var info = 0
    var licenseCRC32 = 0
    var licenseMD5 = ByteArray(16)
    var timestamp = 0L
    var displayName: String? = null
    var activeTargets = 0L
    var functionClassifications = 0L
    var steamAppID = 0
    var debuggerPort = 0
    var roomOrder = intArrayOf()
    var gms2FPS = 0f
}

// ===[ OPTN ]===
class OptnConstant(val name: String?, val value: String?)

class Optn {
    var info = 0L
    var scale = 0
    var windowColor = 0
    var colorDepth = 0
    var resolution = 0
    var frequency = 0
    var vertexSync = 0
    var priority = 0
    var backImage = 0
    var frontImage = 0
    var loadImage = 0
    var loadAlpha = 0
    var constants = emptyList<OptnConstant>()
}

// ===[ LANG ]===
class Language(val name: String?, val region: String?, val entries: List<String?>)

class Lang {
    var unknown1 = 0
    var entryIds = emptyList<String?>()
    var languages = emptyList<Language>()
}

// ===[ EXTN ]===
class ExtensionFunction(
    val name: String?, val id: Int, val kind: Int, val retType: Int,
    val extName: String?, val arguments: IntArray
)

class ExtensionFile(
    val filename: String?, val cleanupScript: String?, val initScript: String?,
    val kind: Int, val functions: List<ExtensionFunction>
)

class Extension(
    val folderName: String?, val name: String?, val className: String?,
    val files: List<ExtensionFile>
)

class Extn {
    var extensions = emptyList<Extension>()
}

// ===[ SOND ]===
class Sound(
    val name: String?, val flags: Int, val type: String?, val file: String?,
    val effects: Int, val volume: Float, val pitch: Float,
    val audioGroup: Int, val audioFile: Int
)

class Sond {
    var sounds = emptyList<Sound>()
}

// ===[ AGRP ]===
class Agrp {
    var audioGroups = emptyList<String?>()
}

// ===[ SPRT ]===
class Sprite {
    var name: String? = null
    var width = 0
    var height = 0
    var marginLeft = 0
    var marginRight = 0
    var marginBottom = 0
    var marginTop = 0
    var transparent = false
    var smooth = false
    var preload = false
    var bboxMode = 0
    var sepMasks = 0
    var originX = 0
    var originY = 0
    var sVersion = 0
    var sSpriteType = 0
    var gms2PlaybackSpeed = 0f
    var gms2PlaybackSpeedType = false
    var specialType = false
    var textureOffsets = intArrayOf()
    var masks: List<ByteArray>? = null
}

class Sprt {
    var sprites = emptyList<Sprite>()
}

// ===[ BGND ]===
class Background {
    var name: String? = null
    var transparent = false
    var smooth = false
    var preload = false
    var textureOffset = 0
    // GMS2 tile set fields
    var gms2UnknownAlways2 = 0
    var gms2TileWidth = 0
    var gms2TileHeight = 0
    var gms2TileSeparationX = 0
    var gms2TileSeparationY = 0
    var gms2OutputBorderX = 0
    var gms2OutputBorderY = 0
    var gms2TileColumns = 0
    var gms2ItemsPerTileCount = 0
    var gms2TileCount = 0
    var gms2ExportedSpriteIndex = 0
    var gms2FrameLength = 0L
    var gms2TileIds = intArrayOf()
}

class Bgnd {
    var backgrounds = emptyList<Background>()
}

// ===[ PATH ]===
class PathPoint(val x: Float, val y: Float, val speed: Float)

class InternalPathPoint(val x: Double, val y: Double, val speed: Double, var l: Double = 0.0)

data class PathPositionResult(val x: Double, val y: Double, val speed: Double)

class GamePath {
    var name: String? = null
    var isSmooth = false
    var isClosed = false
    var precision = 0
    var points = emptyList<PathPoint>()
    var internalPoints = emptyList<InternalPathPoint>()
    var length = 0.0

    fun computeInternal() {
        val temp = mutableListOf<InternalPathPoint>()

        fun addPoint(x: Double, y: Double, speed: Double) {
            temp.add(InternalPathPoint(x, y, speed))
        }

        fun handlePiece(depth: Int, x1: Double, y1: Double, s1: Double, x2: Double, y2: Double, s2: Double, x3: Double, y3: Double, s3: Double) {
            if (depth == 0) return
            val mx = (x1 + x2 + x2 + x3) / 4.0
            val my = (y1 + y2 + y2 + y3) / 4.0
            val ms = (s1 + s2 + s2 + s3) / 4.0
            if ((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) > 16.0) {
                handlePiece(depth - 1, x1, y1, s1, (x2 + x1) / 2.0, (y2 + y1) / 2.0, (s2 + s1) / 2.0, mx, my, ms)
            }
            addPoint(mx, my, ms)
            if ((x2 - x3) * (x2 - x3) + (y2 - y3) * (y2 - y3) > 16.0) {
                handlePiece(depth - 1, mx, my, ms, (x3 + x2) / 2.0, (y3 + y2) / 2.0, (s3 + s2) / 2.0, x3, y3, s3)
            }
        }

        if (points.isEmpty()) {
            internalPoints = emptyList()
            length = 0.0
            return
        }

        if (isSmooth) {
            if (!isClosed) {
                addPoint(points[0].x.toDouble(), points[0].y.toDouble(), points[0].speed.toDouble())
            }
            val n = if (isClosed) points.size - 1 else points.size - 3
            for (i in 0..n) {
                val p1 = points[i % points.size]
                val p2 = points[(i + 1) % points.size]
                val p3 = points[(i + 2) % points.size]
                handlePiece(
                    precision,
                    (p1.x + p2.x) / 2.0, (p1.y + p2.y) / 2.0, (p1.speed + p2.speed) / 2.0,
                    p2.x.toDouble(), p2.y.toDouble(), p2.speed.toDouble(),
                    (p2.x + p3.x) / 2.0, (p2.y + p3.y) / 2.0, (p2.speed + p3.speed) / 2.0
                )
            }
            if (!isClosed) {
                val last = points.last()
                addPoint(last.x.toDouble(), last.y.toDouble(), last.speed.toDouble())
            } else if (temp.isNotEmpty()) {
                addPoint(temp[0].x, temp[0].y, temp[0].speed)
            }
        } else {
            for (p in points) {
                addPoint(p.x.toDouble(), p.y.toDouble(), p.speed.toDouble())
            }
            if (isClosed) {
                addPoint(points[0].x.toDouble(), points[0].y.toDouble(), points[0].speed.toDouble())
            }
        }

        internalPoints = temp
        length = 0.0
        if (internalPoints.isNotEmpty()) {
            internalPoints[0].l = 0.0
            for (i in 1 until internalPoints.size) {
                val dx = internalPoints[i].x - internalPoints[i - 1].x
                val dy = internalPoints[i].y - internalPoints[i - 1].y
                length += sqrt(dx * dx + dy * dy)
                internalPoints[i].l = length
            }
        }
    }

    fun getPosition(t: Double): PathPositionResult {
        if (internalPoints.isEmpty()) return PathPositionResult(0.0, 0.0, 0.0)
        if (internalPoints.size == 1 || length == 0.0 || 0.0 >= t) {
            val p = internalPoints[0]
            return PathPositionResult(p.x, p.y, p.speed)
        }
        if (t >= 1.0) {
            val p = internalPoints.last()
            return PathPositionResult(p.x, p.y, p.speed)
        }
        val l = length * t
        var pos = 0
        while (internalPoints.size - 2 > pos && l >= internalPoints[pos + 1].l) {
            pos++
        }
        val node = internalPoints[pos]
        val lRem = l - node.l
        val w = internalPoints[pos + 1].l - node.l
        if (w != 0.0) {
            val next = internalPoints[pos + 1]
            return PathPositionResult(
                node.x + lRem * (next.x - node.x) / w,
                node.y + lRem * (next.y - node.y) / w,
                node.speed + lRem * (next.speed - node.speed) / w
            )
        }
        return PathPositionResult(node.x, node.y, node.speed)
    }
}

class PathChunk {
    var paths = emptyList<GamePath>()
}

// ===[ SCPT ]===
class Script(val name: String?, val codeId: Int)

class Scpt {
    var scripts = emptyList<Script>()
}

// ===[ GLOB ]===
class Glob {
    var codeIds = intArrayOf()
}

// ===[ SHDR ]===
class Shader {
    var name: String? = null
    var type = 0
    var glslES_Vertex: String? = null
    var glslES_Fragment: String? = null
    var glsl_Vertex: String? = null
    var glsl_Fragment: String? = null
    var hlsl9_Vertex: String? = null
    var hlsl9_Fragment: String? = null
    var hlsl11_VertexOffset = 0
    var hlsl11_PixelOffset = 0
    var vertexAttributes = emptyList<String?>()
    var version = 0
    var pssl_VertexOffset = 0
    var pssl_VertexLen = 0
    var pssl_PixelOffset = 0
    var pssl_PixelLen = 0
    var cgVita_VertexOffset = 0
    var cgVita_VertexLen = 0
    var cgVita_PixelOffset = 0
    var cgVita_PixelLen = 0
    var cgPS3_VertexOffset = 0
    var cgPS3_VertexLen = 0
    var cgPS3_PixelOffset = 0
    var cgPS3_PixelLen = 0
}

class Shdr {
    var shaders = emptyList<Shader>()
}

// ===[ FONT ]===
class KerningPair(val character: Short, val shiftModifier: Short)

class FontGlyph {
    var character = 0
    var sourceX = 0
    var sourceY = 0
    var sourceWidth = 0
    var sourceHeight = 0
    var shift: Short = 0
    var offset: Short = 0
    var kerning = emptyList<KerningPair>()
}

class Font {
    var name: String? = null
    var displayName: String? = null
    var emSize = 0
    var bold = false
    var italic = false
    var rangeStart = 0
    var charset = 0
    var antiAliasing = 0
    var rangeEnd = 0
    var textureOffset = 0
    var scaleX = 0f
    var scaleY = 0f
    var ascenderOffset = 0
    var ascender = 0
    var sdfSpread = 0
    var lineHeight = 0
    var hasAscender = false
    var hasSDFSpread = false
    var hasLineHeight = false
    var glyphs = emptyList<FontGlyph>()
    var maxGlyphHeight = 0
    var isSpriteFont = false
    var spriteIndex = -1
}

class FontChunk {
    var fonts = emptyList<Font>()
}

// ===[ EventAction (shared by TMLN and OBJT) ]===
class EventAction {
    var libID = 0
    var id = 0
    var kind = 0
    var useRelative = false
    var isQuestion = false
    var useApplyTo = false
    var exeType = 0
    var actionName: String? = null
    var codeId = 0
    var argumentCount = 0
    var who = 0
    var relative = false
    var isNot = false
    var unknownAlwaysZero = 0
}

// ===[ TMLN ]===
class TimelineMoment(val step: Int, val actions: List<EventAction>)

class Timeline(val name: String?, val moments: List<TimelineMoment>)

class Tmln {
    var timelines = emptyList<Timeline>()
}

// ===[ OBJT ]===
const val OBJT_EVENT_TYPE_COUNT = 15

class ObjectEvent(val eventSubtype: Int, val actions: List<EventAction>)

class ObjectEventList(val events: List<ObjectEvent> = emptyList())

class PhysicsVertex(val x: Float, val y: Float)

class GameObject {
    var name: String? = null
    var spriteId = 0
    var visible = false
    var managed = false
    var solid = false
    var depth = 0
    var persistent = false
    var parentId = 0
    var textureMaskId = 0
    var usesPhysics = false
    var isSensor = false
    var collisionShape = 0
    var density = 0f
    var restitution = 0f
    var group = 0
    var linearDamping = 0f
    var angularDamping = 0f
    var physicsVertexCount = 0
    var friction = 0f
    var awake = false
    var kinematic = false
    var physicsVertices = emptyList<PhysicsVertex>()
    var eventLists = Array(OBJT_EVENT_TYPE_COUNT) { ObjectEventList() }
}

class Objt {
    var objects = emptyList<GameObject>()
}

// ===[ ROOM ]===
class RoomBackground {
    var enabled = false
    var foreground = false
    var backgroundDefinition = 0
    var x = 0
    var y = 0
    var tileX = 0
    var tileY = 0
    var speedX = 0
    var speedY = 0
    var stretch = false
}

class RoomView {
    var enabled = false
    var viewX = 0
    var viewY = 0
    var viewWidth = 0
    var viewHeight = 0
    var portX = 0
    var portY = 0
    var portWidth = 0
    var portHeight = 0
    var borderX = 0
    var borderY = 0
    var speedX = 0
    var speedY = 0
    var objectId = 0
}

class RoomGameObject {
    var x = 0
    var y = 0
    var objectDefinition = 0
    var instanceID = 0
    var creationCode = 0
    var scaleX = 0f
    var scaleY = 0f
    var imageSpeed = 1.0f
    var imageIndex = 0
    var color = 0
    var rotation = 0f
    var preCreateCode = -1
}

class RoomTile {
    var x = 0
    var y = 0
    var useSpriteDefinition = false
    var backgroundDefinition = 0
    var sourceX = 0
    var sourceY = 0
    var width = 0
    var height = 0
    var tileDepth = 0
    var instanceID = 0
    var scaleX = 0f
    var scaleY = 0f
    var color = 0
}

// GMS2 room layer types
object RoomLayerType {
    const val PATH = 0
    const val BACKGROUND = 1
    const val INSTANCES = 2
    const val ASSETS = 3
    const val TILES = 4
    const val EFFECT = 6
    const val PATH2 = 7
}

class SpriteInstance {
    var name: String? = null
    var spriteIndex = 0
    var x = 0
    var y = 0
    var scaleX = 0f
    var scaleY = 0f
    var color = 0
    var animationSpeed = 0f
    var animationSpeedType = 0
    var frameIndex = 0f
    var rotation = 0f
}

class RoomLayerAssetsData {
    var legacyTiles = emptyList<RoomTile>()
    var sprites = emptyList<SpriteInstance>()
}

class RoomLayerBackgroundData {
    var visible = false
    var foreground = false
    var spriteIndex = -1
    var hTiled = false
    var vTiled = false
    var stretch = false
    var color = 0
    var firstFrame = 0f
    var animSpeed = 0f
    var animSpeedType = 0
}

class RoomLayerInstancesData {
    var instanceIds = intArrayOf()
}

class RoomLayerTilesData {
    var backgroundIndex = 0
    var tilesX = 0
    var tilesY = 0
    var tileData = intArrayOf()
}

class RoomLayer {
    var name: String? = null
    var id = 0
    var type = 0
    var depth = 0
    var xOffset = 0f
    var yOffset = 0f
    var hSpeed = 0f
    var vSpeed = 0f
    var visible = false
    var assetsData: RoomLayerAssetsData? = null
    var backgroundData: RoomLayerBackgroundData? = null
    var instancesData: RoomLayerInstancesData? = null
    var tilesData: RoomLayerTilesData? = null
}

class Room {
    var name: String? = null
    var caption: String? = null
    var width = 0
    var height = 0
    var speed = 0
    var persistent = false
    var backgroundColor = 0
    var drawBackgroundColor = false
    var creationCodeId = 0
    var flags = 0
    var world = false
    var top = 0
    var left = 0
    var right = 0
    var bottom = 0
    var gravityX = 0f
    var gravityY = 0f
    var metersPerPixel = 0f
    var backgrounds = Array(8) { RoomBackground() }
    var views = Array(8) { RoomView() }
    var gameObjects = emptyList<RoomGameObject>()
    var tiles = emptyList<RoomTile>()
    var layers = emptyList<RoomLayer>()
}

class RoomChunk {
    var rooms = emptyList<Room>()
}

// ===[ TPAG ]===
class TexturePageItem {
    var sourceX = 0
    var sourceY = 0
    var sourceWidth = 0
    var sourceHeight = 0
    var targetX = 0
    var targetY = 0
    var targetWidth = 0
    var targetHeight = 0
    var boundingWidth = 0
    var boundingHeight = 0
    var texturePageId: Short = 0
}

class Tpag {
    var items = emptyList<TexturePageItem>()
}

// ===[ CODE ]===
class CodeEntry(
    val name: String?, val length: Int, val localsCount: Int, val argumentsCount: Int,
    val bytecodeAbsoluteOffset: Int, val offset: Int
)

class Code {
    var entries = emptyList<CodeEntry>()
}

// ===[ VARI ]===
class Variable(
    val name: String?, val instanceType: Int, val varID: Int,
    val occurrences: Int, val firstAddress: Int,
    var builtinVarId: Short = -1
)

class Vari {
    var varCount1 = 0
    var varCount2 = 0
    var maxLocalVarCount = 0
    var variables = emptyList<Variable>()
}

// ===[ FUNC ]===
class Function(val name: String?, val occurrences: Int, val firstAddress: Int)

class LocalVar(val varID: Int, val name: String?)

class CodeLocals(val name: String?, val locals: List<LocalVar>)

class Func {
    var functions = emptyList<Function>()
    var codeLocals = emptyList<CodeLocals>()
}

// ===[ STRG ]===
class Strg {
    var strings = emptyList<String>()
}

// ===[ TXTR ]===
class Texture(
    val scaled: Int,
    val blobOffset: Int,
    val blobSize: Int,
    val blobData: ByteArray?,
    val generatedMips: Int = 0,
    val textureBlockSize: Int = 0,
    val textureWidth: Int = 0,
    val textureHeight: Int = 0,
    val indexInGroup: Int = 0
)

class Txtr {
    var textures = emptyList<Texture>()
}

// ===[ AUDO ]===
class AudioEntry(val dataSize: Int, val dataOffset: Int, val data: ByteArray?)

class Audo {
    var entries = emptyList<AudioEntry>()
}

// ===[ Top-level DataWin container ]===
class DataWin {
    var gen8 = Gen8()
    var optn = Optn()
    var lang = Lang()
    var extn = Extn()
    var sond = Sond()
    var agrp = Agrp()
    var sprt = Sprt()
    var bgnd = Bgnd()
    var path = PathChunk()
    var scpt = Scpt()
    var glob = Glob()
    var shdr = Shdr()
    var font = FontChunk()
    var tmln = Tmln()
    var objt = Objt()
    var room = RoomChunk()
    var tpag = Tpag()
    var code = Code()
    var vari = Vari()
    var func = Func()
    var strg = Strg()
    var txtr = Txtr()
    var audo = Audo()

    var bytecodeBuffer: ByteArray? = null
    var bytecodeBufferBase = 0

    val tpagOffsetMap = HashMap<Int, Int>()
    val sprtOffsetMap = HashMap<Int, Int>()

    // Effective GMS version after heuristic detection. GEN8.version is unreliable since GM:S 2,
    // so chunk parsers probe the data and bump these fields upward when they detect newer-format features.
    var detectedMajor = 0
    var detectedMinor = 0
    var detectedRelease = 0
    var detectedBuild = 0

    fun resolveTPAG(offset: Int): Int = tpagOffsetMap[offset] ?: -1
    fun resolveSPRT(offset: Int): Int = sprtOffsetMap[offset] ?: -1

    fun isVersionAtLeast(major: Int, minor: Int, release: Int, build: Int): Boolean {
        if (detectedMajor != major) return detectedMajor > major
        if (detectedMinor != minor) return detectedMinor > minor
        if (detectedRelease != release) return detectedRelease > release
        return detectedBuild >= build
    }

    fun bumpVersionTo(major: Int, minor: Int, release: Int, build: Int) {
        if (isVersionAtLeast(major, minor, release, build)) return
        detectedMajor = major
        detectedMinor = minor
        detectedRelease = release
        detectedBuild = build
    }

    companion object {
        fun parse(bytes: ByteArray, options: DataWinParserOptions = DataWinParserOptions()): DataWin {
            val reader = BinaryReader(bytes)
            val dw = DataWin()

            // Validate FORM header
            val magic = reader.readChunkName()
            require(magic == "FORM") { "Invalid file: expected FORM magic, got '$magic'" }
            reader.readInt32() // form length

            // Pass 1: scan chunk names to bump the detected version based on chunk presence.
            // Some later-introduced chunks (ACRV/SEQN/TAGS/FEDS/EMBI for 2.3, FEAT for 2022.8, etc.)
            // appear AFTER OBJT in file order. parseOBJT needs to know we're >= 2.3 to probe for
            // the GMS 2022.5+ Managed field, so we have to bump versions before parsing anything.
            val passOnePosition = reader.position
            while (reader.size > reader.position) {
                if (reader.position + 8 > reader.size) break
                val name = reader.readChunkName()
                val len = reader.readInt32()
                when (name) {
                    "FEDS", "ACRV", "SEQN", "TAGS", "EMBI" -> dw.bumpVersionTo(2, 3, 0, 0)
                    "FEAT" -> dw.bumpVersionTo(2022, 8, 0, 0)
                    "PSEM", "PSYS" -> dw.bumpVersionTo(2023, 4, 0, 0)
                    "UILR" -> dw.bumpVersionTo(2024, 13, 0, 0)
                }
                reader.position += len
            }
            reader.position = passOnePosition

            // Pass 2: actually parse the chunks. Since the entire file is in memory, readStringPtr
            // can seek to any offset at any time. No need for a separate STRG pre-load pass.
            while (reader.size > reader.position) {
                if (reader.position + 8 > reader.size) break
                val chunkName = reader.readChunkName()
                val chunkLength = reader.readInt32()
                val chunkDataStart = reader.position
                val chunkEnd = chunkDataStart + chunkLength

                when {
                    options.parseGen8 && chunkName == "GEN8" -> parseGEN8(reader, dw)
                    options.parseOptn && chunkName == "OPTN" -> parseOPTN(reader, dw)
                    options.parseLang && chunkName == "LANG" -> parseLANG(reader, dw)
                    options.parseExtn && chunkName == "EXTN" -> parseEXTN(reader, dw)
                    options.parseSond && chunkName == "SOND" -> parseSOND(reader, dw)
                    options.parseAgrp && chunkName == "AGRP" -> parseAGRP(reader, dw)
                    options.parseSprt && chunkName == "SPRT" -> parseSPRT(reader, dw, options.skipLoadingPreciseMasksForNonPreciseSprites)
                    options.parseBgnd && chunkName == "BGND" -> parseBGND(reader, dw)
                    options.parsePath && chunkName == "PATH" -> parsePATH(reader, dw)
                    options.parseScpt && chunkName == "SCPT" -> parseSCPT(reader, dw)
                    options.parseGlob && chunkName == "GLOB" -> parseGLOB(reader, dw)
                    options.parseShdr && chunkName == "SHDR" -> parseSHDR(reader, dw)
                    options.parseFont && chunkName == "FONT" -> parseFONT(reader, dw)
                    options.parseTmln && chunkName == "TMLN" -> parseTMLN(reader, dw)
                    options.parseObjt && chunkName == "OBJT" -> parseOBJT(reader, dw)
                    options.parseRoom && chunkName == "ROOM" -> parseROOM(reader, dw)
                    chunkName == "DAFL" -> { /* empty chunk */ }
                    options.parseTpag && chunkName == "TPAG" -> parseTPAG(reader, dw)
                    options.parseCode && chunkName == "CODE" -> parseCODE(reader, dw, chunkLength, chunkDataStart)
                    options.parseVari && chunkName == "VARI" -> parseVARI(reader, dw, chunkLength)
                    options.parseFunc && chunkName == "FUNC" -> parseFUNC(reader, dw)
                    options.parseStrg && chunkName == "STRG" -> parseSTRG(reader, dw)
                    options.parseTxtr && chunkName == "TXTR" -> parseTXTR(reader, dw, chunkEnd)
                    options.parseAudo && chunkName == "AUDO" -> parseAUDO(reader, dw)
                    else -> println("Unknown chunk: $chunkName (length $chunkLength at offset 0x${(chunkDataStart - 8).toString(16)})")
                }

                reader.position = chunkEnd
            }

            println("Done!")

            return dw
        }

        // ===[ Helpers ]===

        private fun readEventActions(reader: BinaryReader): List<EventAction> {
            val ptrs = reader.readPointerTable()
            if (ptrs.isEmpty()) return emptyList()
            return ptrs.map { ptr ->
                reader.position = ptr
                EventAction().apply {
                    libID = reader.readInt32()
                    id = reader.readInt32()
                    kind = reader.readInt32()
                    useRelative = reader.readBool32()
                    isQuestion = reader.readBool32()
                    useApplyTo = reader.readBool32()
                    exeType = reader.readInt32()
                    actionName = reader.readStringPtr()
                    codeId = reader.readInt32()
                    argumentCount = reader.readInt32()
                    who = reader.readInt32()
                    relative = reader.readBool32()
                    isNot = reader.readBool32()
                    unknownAlwaysZero = reader.readInt32()
                }
            }
        }

        // ===[ Chunk Parsers ]===

        private fun parseGEN8(reader: BinaryReader, dw: DataWin) {
            val g = dw.gen8
            g.isDebuggerDisabled = reader.readUint8()
            g.bytecodeVersion = reader.readUint8()
            reader.skip(2) // padding
            g.fileName = reader.readStringPtr()
            g.config = reader.readStringPtr()
            g.lastObj = reader.readInt32()
            g.lastTile = reader.readInt32()
            g.gameID = reader.readInt32()
            g.directPlayGuid = reader.readBytes(16)
            g.name = reader.readStringPtr()
            g.major = reader.readInt32()
            g.minor = reader.readInt32()
            g.release = reader.readInt32()
            g.build = reader.readInt32()
            g.defaultWindowWidth = reader.readInt32()
            g.defaultWindowHeight = reader.readInt32()
            g.info = reader.readInt32()
            g.licenseCRC32 = reader.readInt32()
            g.licenseMD5 = reader.readBytes(16)
            g.timestamp = reader.readLong()
            g.displayName = reader.readStringPtr()
            g.activeTargets = reader.readLong()
            g.functionClassifications = reader.readLong()
            g.steamAppID = reader.readInt32()
            if (g.bytecodeVersion >= 14) {
                g.debuggerPort = reader.readInt32()
            }
            val roomOrderCount = reader.readInt32()
            g.roomOrder = IntArray(roomOrderCount) { reader.readInt32() }

            if (g.major >= 2) {
                reader.skip(8)    // firstRandom (int64)
                reader.skip(8 * 4) // 4 Random Entries
                g.gms2FPS = reader.readFloat32()
                reader.skip(4)    // AllowStatistics (bool32)
                reader.skip(16)   // GameGUID (16 bytes)
            }

            // Seed the detected version from GEN8.
            // Later chunk parsers may bump these upward when they identify newer-format features, because since GM:S 2 the value in the GEN8 chunk is not accurate.
            dw.bumpVersionTo(g.major, g.minor, g.release, g.build)
        }

        private fun parseOPTN(reader: BinaryReader, dw: DataWin) {
            val o = dw.optn
            val marker = reader.readInt32()
            require(marker == Int.MIN_VALUE) { "OPTN: expected new format marker 0x80000000, got 0x${marker.toUInt().toString(16)}" }
            reader.readInt32() // shaderExtVersion (always 2)
            o.info = reader.readLong()
            o.scale = reader.readInt32()
            o.windowColor = reader.readInt32()
            o.colorDepth = reader.readInt32()
            o.resolution = reader.readInt32()
            o.frequency = reader.readInt32()
            o.vertexSync = reader.readInt32()
            o.priority = reader.readInt32()
            o.backImage = reader.readInt32()
            o.frontImage = reader.readInt32()
            o.loadImage = reader.readInt32()
            o.loadAlpha = reader.readInt32()
            val constantCount = reader.readInt32()
            o.constants = List(constantCount) { OptnConstant(reader.readStringPtr(), reader.readStringPtr()) }
        }

        private fun parseLANG(reader: BinaryReader, dw: DataWin) {
            val l = dw.lang
            l.unknown1 = reader.readInt32()
            val languageCount = reader.readInt32()
            val entryCount = reader.readInt32()
            l.entryIds = List(entryCount) { reader.readStringPtr() }
            l.languages = List(languageCount) {
                val name = reader.readStringPtr()
                val region = reader.readStringPtr()
                Language(name, region, List(entryCount) { reader.readStringPtr() })
            }
        }

        private fun parseEXTN(reader: BinaryReader, dw: DataWin) {
            val extPtrs = reader.readPointerTable()
            dw.extn.extensions = extPtrs.map { extPtr ->
                reader.position = extPtr
                val folderName = reader.readStringPtr()
                val name = reader.readStringPtr()
                val className = reader.readStringPtr()
                val filePtrs = reader.readPointerTable()
                val files = filePtrs.map { filePtr ->
                    reader.position = filePtr
                    val filename = reader.readStringPtr()
                    val cleanupScript = reader.readStringPtr()
                    val initScript = reader.readStringPtr()
                    val kind = reader.readInt32()
                    val funcPtrs = reader.readPointerTable()
                    val functions = funcPtrs.map { funcPtr ->
                        reader.position = funcPtr
                        val fName = reader.readStringPtr()
                        val id = reader.readInt32()
                        val fKind = reader.readInt32()
                        val retType = reader.readInt32()
                        val extName = reader.readStringPtr()
                        val argCount = reader.readInt32()
                        val arguments = IntArray(argCount) { reader.readInt32() }
                        ExtensionFunction(fName, id, fKind, retType, extName, arguments)
                    }
                    ExtensionFile(filename, cleanupScript, initScript, kind, functions)
                }
                Extension(folderName, name, className, files)
            }
        }

        private fun parseSOND(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.sond.sounds = ptrs.map { ptr ->
                reader.position = ptr
                val name = reader.readStringPtr()
                val flags = reader.readInt32()
                val type = reader.readStringPtr()
                val file = reader.readStringPtr()
                val effects = reader.readInt32()
                val volume = reader.readFloat32()
                val pitch = reader.readFloat32()
                val audioGroup = if ((flags and 0x64) == 0x64) {
                    reader.readInt32()
                } else {
                    reader.readInt32() // preload, discard
                    0
                }
                val audioFile = reader.readInt32()
                Sound(name, flags, type, file, effects, volume, pitch, audioGroup, audioFile)
            }
        }

        private fun parseAGRP(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.agrp.audioGroups = ptrs.map { ptr ->
                reader.position = ptr
                reader.readStringPtr()
            }
        }

        private fun parseSPRT(reader: BinaryReader, dw: DataWin, skipNonPrecise: Boolean) {
            val ptrs = reader.readPointerTable()
            dw.sprt.sprites = ptrs.map { ptr ->
                reader.position = ptr
                Sprite().apply {
                    name = reader.readStringPtr()
                    width = reader.readInt32()
                    height = reader.readInt32()
                    marginLeft = reader.readInt32()
                    marginRight = reader.readInt32()
                    marginBottom = reader.readInt32()
                    marginTop = reader.readInt32()
                    transparent = reader.readBool32()
                    smooth = reader.readBool32()
                    preload = reader.readBool32()
                    bboxMode = reader.readInt32()
                    sepMasks = reader.readInt32()
                    originX = reader.readInt32()
                    originY = reader.readInt32()

                    // Detect special type (GMS2) vs normal: peek next int32
                    var textureCount = reader.readInt32()
                    if (textureCount == -1) {
                        specialType = true
                        sVersion = reader.readInt32()
                        sSpriteType = reader.readInt32()
                        if (dw.isVersionAtLeast(2, 0, 0, 0)) {
                            gms2PlaybackSpeed = reader.readFloat32()
                            gms2PlaybackSpeedType = reader.readBool32()
                            if (sVersion >= 2) {
                                reader.skip(4) // sequenceOffset
                                if (sVersion >= 3) {
                                    reader.skip(4) // nineSliceOffset
                                }
                            }
                            textureCount = reader.readInt32()
                        }
                    }

                    textureOffsets = IntArray(textureCount) { reader.readInt32() }

                    // Collision masks
                    // sepMasks: 0 = axis-aligned rect, 1 = precise per-frame, 2 = rotated rect
                    // Mask format: each bit = 1 pixel, MSB first, row-major.
                    // After ALL masks, the total mask data is padded to 4-byte alignment (not per-mask).
                    val maskDataCount = reader.readInt32()
                    if (maskDataCount > 0 && width > 0 && height > 0) {
                        val bytesPerRow = (width + 7) / 8
                        val bytesPerMask = bytesPerRow * height
                        if (sepMasks == 1 || !skipNonPrecise) {
                            masks = List(maskDataCount) { reader.readBytes(bytesPerMask) }
                        } else {
                            reader.skip(bytesPerMask * maskDataCount)
                        }
                        val totalMaskBytes = bytesPerMask * maskDataCount
                        val remainder = totalMaskBytes % 4
                        if (remainder != 0) reader.skip(4 - remainder)
                    }
                }
            }

            // Build sprtOffsetMap: absolute file offset -> SPRT index
            for (i in ptrs.indices) {
                dw.sprtOffsetMap[ptrs[i]] = i
            }
        }

        private fun parseBGND(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.bgnd.backgrounds = ptrs.map { ptr ->
                reader.position = ptr
                Background().apply {
                    name = reader.readStringPtr()
                    transparent = reader.readBool32()
                    smooth = reader.readBool32()
                    preload = reader.readBool32()
                    textureOffset = reader.readInt32()
                    if (dw.isVersionAtLeast(2, 0, 0, 0)) {
                        gms2UnknownAlways2 = reader.readInt32()
                        gms2TileWidth = reader.readInt32()
                        gms2TileHeight = reader.readInt32()
                        if (dw.isVersionAtLeast(2024, 14, 0, 1)) {
                            gms2TileSeparationX = reader.readInt32()
                            gms2TileSeparationY = reader.readInt32()
                        }
                        gms2OutputBorderX = reader.readInt32()
                        gms2OutputBorderY = reader.readInt32()
                        gms2TileColumns = reader.readInt32()
                        gms2ItemsPerTileCount = reader.readInt32()
                        gms2TileCount = reader.readInt32()
                        gms2ExportedSpriteIndex = reader.readInt32()
                        gms2FrameLength = reader.readLong()
                        val tileIdCount = gms2TileCount * gms2ItemsPerTileCount
                        gms2TileIds = IntArray(tileIdCount) { reader.readInt32() }
                    }
                }
            }
        }

        private fun parsePATH(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.path.paths = ptrs.map { ptr ->
                reader.position = ptr
                GamePath().apply {
                    name = reader.readStringPtr()
                    isSmooth = reader.readBool32()
                    isClosed = reader.readBool32()
                    precision = reader.readInt32()
                    val pointCount = reader.readInt32()
                    points = List(pointCount) {
                        PathPoint(reader.readFloat32(), reader.readFloat32(), reader.readFloat32())
                    }
                    computeInternal()
                }
            }
        }

        private fun parseSCPT(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.scpt.scripts = ptrs.map { ptr ->
                reader.position = ptr
                Script(reader.readStringPtr(), reader.readInt32())
            }
        }

        private fun parseGLOB(reader: BinaryReader, dw: DataWin) {
            val count = reader.readInt32()
            dw.glob.codeIds = IntArray(count) { reader.readInt32() }
        }

        private fun parseSHDR(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.shdr.shaders = ptrs.map { ptr ->
                reader.position = ptr
                Shader().apply {
                    name = reader.readStringPtr()
                    type = reader.readInt32() and 0x7FFFFFFF
                    glslES_Vertex = reader.readStringPtr()
                    glslES_Fragment = reader.readStringPtr()
                    glsl_Vertex = reader.readStringPtr()
                    glsl_Fragment = reader.readStringPtr()
                    hlsl9_Vertex = reader.readStringPtr()
                    hlsl9_Fragment = reader.readStringPtr()
                    hlsl11_VertexOffset = reader.readInt32()
                    hlsl11_PixelOffset = reader.readInt32()
                    val attrCount = reader.readInt32()
                    vertexAttributes = List(attrCount) { reader.readStringPtr() }
                    version = reader.readInt32()
                    pssl_VertexOffset = reader.readInt32()
                    pssl_VertexLen = reader.readInt32()
                    pssl_PixelOffset = reader.readInt32()
                    pssl_PixelLen = reader.readInt32()
                    cgVita_VertexOffset = reader.readInt32()
                    cgVita_VertexLen = reader.readInt32()
                    cgVita_PixelOffset = reader.readInt32()
                    cgVita_PixelLen = reader.readInt32()
                    if (version >= 2) {
                        cgPS3_VertexOffset = reader.readInt32()
                        cgPS3_VertexLen = reader.readInt32()
                        cgPS3_PixelOffset = reader.readInt32()
                        cgPS3_PixelLen = reader.readInt32()
                    }
                }
            }
        }

        private fun parseFONT(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            if (ptrs.isEmpty()) return

            // Probe how many uint32 optional fields exist between scaleY and the glyph PointerList.
            // Optional fields appear in this order when present: AscenderOffset (BC17+), Ascender, SDFSpread, LineHeight.
            var fontOptionalCount = if (dw.gen8.bytecodeVersion >= 17) 1 else 0
            run {
                val baseAfterScaleY = ptrs[0] + 40
                var trial = fontOptionalCount
                while (4 >= trial) {
                    val listStart = baseAfterScaleY + 4 * trial
                    reader.position = listStart
                    val probedGlyphCount = reader.readInt32()
                    if (probedGlyphCount == 0 || probedGlyphCount > 0x10000) {
                        trial++
                        continue
                    }
                    val probedFirstPtr = reader.readInt32()
                    val expectedFirstPtr = listStart + 4 + 4 * probedGlyphCount
                    if (probedFirstPtr == expectedFirstPtr) {
                        fontOptionalCount = trial
                        break
                    }
                    trial++
                }
            }

            dw.font.fonts = ptrs.map { ptr ->
                reader.position = ptr
                Font().apply {
                    name = reader.readStringPtr()
                    displayName = reader.readStringPtr()
                    emSize = reader.readInt32()
                    bold = reader.readBool32()
                    italic = reader.readBool32()
                    rangeStart = reader.readUint16()
                    charset = reader.readUint8()
                    antiAliasing = reader.readUint8()
                    rangeEnd = reader.readInt32()
                    textureOffset = reader.readInt32()
                    scaleX = reader.readFloat32()
                    scaleY = reader.readFloat32()
                    var readSoFar = 0
                    if (dw.gen8.bytecodeVersion >= 17 && fontOptionalCount > readSoFar) {
                        ascenderOffset = reader.readInt32()
                        readSoFar++
                    }
                    if (fontOptionalCount > readSoFar) {
                        ascender = reader.readInt32()
                        hasAscender = true
                        readSoFar++
                    }
                    if (fontOptionalCount > readSoFar) {
                        sdfSpread = reader.readInt32()
                        hasSDFSpread = true
                        readSoFar++
                    }
                    if (fontOptionalCount > readSoFar) {
                        lineHeight = reader.readInt32()
                        hasLineHeight = true
                        readSoFar++
                    }
                    val glyphPtrs = reader.readPointerTable()
                    var maxH = 0
                    glyphs = glyphPtrs.map { glyphPtr ->
                        reader.position = glyphPtr
                        FontGlyph().apply {
                            character = reader.readUint16()
                            sourceX = reader.readUint16()
                            sourceY = reader.readUint16()
                            sourceWidth = reader.readUint16()
                            sourceHeight = reader.readUint16()
                            shift = reader.readInt16()
                            offset = reader.readInt16()
                            if (sourceHeight > maxH) maxH = sourceHeight
                            val kerningCount = reader.readUint16()
                            kerning = List(kerningCount) {
                                KerningPair(reader.readInt16(), reader.readInt16())
                            }
                        }
                    }
                    maxGlyphHeight = maxH
                }
            }
        }

        private fun parseTMLN(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.tmln.timelines = ptrs.map { ptr ->
                reader.position = ptr
                val name = reader.readStringPtr()
                val momentCount = reader.readInt32()
                if (momentCount > 0) {
                    // Pass 1: read step + event pointer pairs
                    val moments = Array(momentCount) {
                        Pair(reader.readInt32(), reader.readInt32()) // step, eventPtr
                    }
                    // Pass 2: parse event actions
                    Timeline(name, moments.map { (step, eventPtr) ->
                        reader.position = eventPtr
                        TimelineMoment(step, readEventActions(reader))
                    })
                } else {
                    Timeline(name, emptyList())
                }
            }
        }

        private fun parseOBJT(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()

            // Detect GMS 2022.5+ by probing the first game object's event list structure.
            if (ptrs.isNotEmpty() && dw.isVersionAtLeast(2, 3, 0, 0) && !dw.isVersionAtLeast(2022, 5, 0, 0)) {
                // Skip the 16 fixed uint32 header fields (name..angularDamping) to reach physicsVertexCount.
                reader.position = ptrs[0] + 16 * 4
                val vertexCount = reader.readInt32()
                if (vertexCount >= 0) {
                    // Skip friction + awake + kinematic (12 bytes) and physics vertices (8 bytes each).
                    reader.skip(12 + vertexCount * 8)
                    val eventTypeCount = reader.readInt32()
                    var isOldFormat = false
                    if (eventTypeCount == OBJT_EVENT_TYPE_COUNT) {
                        val firstSubEventPtr = reader.readInt32()
                        val currentAbsPos = reader.position
                        // The remaining 14 outer-list pointers sit between here and the first sub-event list.
                        if (firstSubEventPtr == currentAbsPos + 14 * 4) {
                            isOldFormat = true
                        }
                    }
                    if (!isOldFormat) {
                        dw.bumpVersionTo(2022, 5, 0, 0)
                    }
                }
            }

            dw.objt.objects = ptrs.map { ptr ->
                reader.position = ptr
                GameObject().apply {
                    name = reader.readStringPtr()
                    spriteId = reader.readInt32()
                    visible = reader.readBool32()
                    if (dw.isVersionAtLeast(2022, 5, 0, 0)) {
                        managed = reader.readBool32()
                    }
                    solid = reader.readBool32()
                    depth = reader.readInt32()
                    persistent = reader.readBool32()
                    parentId = reader.readInt32()
                    textureMaskId = reader.readInt32()
                    usesPhysics = reader.readBool32()
                    isSensor = reader.readBool32()
                    collisionShape = reader.readInt32()
                    density = reader.readFloat32()
                    restitution = reader.readFloat32()
                    group = reader.readInt32()
                    linearDamping = reader.readFloat32()
                    angularDamping = reader.readFloat32()
                    physicsVertexCount = reader.readInt32()
                    friction = reader.readFloat32()
                    awake = reader.readBool32()
                    kinematic = reader.readBool32()

                    if (physicsVertexCount > 0) {
                        physicsVertices = List(physicsVertexCount) {
                            PhysicsVertex(reader.readFloat32(), reader.readFloat32())
                        }
                    }

                    // Events: PointerList<PointerList<Event>>
                    val eventTypePtrs = reader.readPointerTable()
                    for (eventType in eventTypePtrs.indices) {
                        if (OBJT_EVENT_TYPE_COUNT > eventType) {
                            reader.position = eventTypePtrs[eventType]
                            val eventPtrs = reader.readPointerTable()
                            eventLists[eventType] = ObjectEventList(eventPtrs.map { eventPtr ->
                                reader.position = eventPtr
                                ObjectEvent(reader.readInt32(), readEventActions(reader))
                            })
                        }
                    }
                }
            }
        }

        private fun parseROOM(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            if (ptrs.isEmpty()) return

            // Detect whether RoomGameObject includes ImageSpeed/ImageIndex fields (added in GMS 2.2.2.302).
            // UndertaleModTool detects this via the distance between the first two game object pointers: 40 bytes = legacy, 48 bytes = with ImageSpeed+ImageIndex.
            if (dw.isVersionAtLeast(2, 0, 0, 0) && !dw.isVersionAtLeast(2, 2, 2, 302)) {
                for (i in ptrs.indices) {
                    reader.position = ptrs[i]
                    // Room header layout (before gameObjectsPtr): name, caption, width, height, speed, persistent,
                    // bgColor, drawBgColor, creationCodeId, flags, backgroundsPtr, viewsPtr = 12 uint32s.
                    reader.skip(12 * 4)
                    val gameObjectsPtr = reader.readInt32()
                    reader.position = gameObjectsPtr
                    val objCount = reader.readInt32()
                    if (objCount >= 2) {
                        val firstPtr = reader.readInt32()
                        val secondPtr = reader.readInt32()
                        if (secondPtr - firstPtr == 48) {
                            dw.bumpVersionTo(2, 2, 2, 302)
                        }
                        break
                    }
                }
            }

            // Detect whether Layer headers include EffectEnabled/EffectType/EffectProperties fields (added in GMS 2022.1).
            if (dw.isVersionAtLeast(2, 3, 0, 0) && !dw.isVersionAtLeast(2022, 1, 0, 0)) {
                for (i in ptrs.indices) {
                    reader.position = ptrs[i]
                    // Room header before layersPtr: 22 uint32s (name..metersPerPixel).
                    reader.skip(22 * 4)
                    val layersPtr = reader.readInt32()
                    val seqnPtr = reader.readInt32()
                    reader.position = layersPtr
                    val layerCount = reader.readInt32()
                    if (layerCount == 0) continue
                    val jumpOffset = reader.readInt32()
                    val nextOffset = if (layerCount == 1) seqnPtr else reader.readInt32()
                    // Layer header: name(4) id(4) type(4) ... we seek to jumpOffset+8 to skip name+id then read type.
                    reader.position = jumpOffset + 8
                    val layerType = reader.readInt32()
                    if (layerType == RoomLayerType.PATH || layerType == RoomLayerType.PATH2) continue
                    var detected = false
                    when (layerType) {
                        RoomLayerType.BACKGROUND -> {
                            val absPos = reader.position
                            if (nextOffset - absPos > 16 * 4) detected = true
                        }
                        RoomLayerType.INSTANCES -> {
                            reader.skip(6 * 4)
                            val instanceCount = reader.readInt32()
                            val absPos = reader.position
                            if (nextOffset - absPos != instanceCount * 4) detected = true
                        }
                        RoomLayerType.ASSETS -> {
                            reader.skip(6 * 4)
                            val tileOffset = reader.readInt32()
                            val absPos = reader.position
                            if (tileOffset != absPos + 8 && tileOffset != absPos + 12) detected = true
                        }
                        RoomLayerType.TILES -> {
                            reader.skip(7 * 4)
                            val tileMapWidth = reader.readInt32()
                            val tileMapHeight = reader.readInt32()
                            val absPos = reader.position
                            if (nextOffset - absPos != tileMapWidth * tileMapHeight * 4) detected = true
                        }
                        RoomLayerType.EFFECT -> {
                            reader.skip(7 * 4)
                            val propertyCount = reader.readInt32()
                            val absPos = reader.position
                            if (nextOffset - absPos != propertyCount * 3 * 4) detected = true
                        }
                    }
                    if (detected) dw.bumpVersionTo(2022, 1, 0, 0)
                    break
                }
            }

            dw.room.rooms = ptrs.map { ptr ->
                reader.position = ptr
                Room().apply {
                    name = reader.readStringPtr()
                    caption = reader.readStringPtr()
                    width = reader.readInt32()
                    height = reader.readInt32()
                    speed = reader.readInt32()
                    persistent = reader.readBool32()
                    backgroundColor = reader.readInt32()
                    drawBackgroundColor = reader.readBool32()
                    creationCodeId = reader.readInt32()
                    flags = reader.readInt32()
                    val backgroundsPtr = reader.readInt32()
                    val viewsPtr = reader.readInt32()
                    val gameObjectsPtr = reader.readInt32()
                    val tilesPtr = reader.readInt32()
                    world = reader.readBool32()
                    top = reader.readInt32()
                    left = reader.readInt32()
                    right = reader.readInt32()
                    bottom = reader.readInt32()
                    gravityX = reader.readFloat32()
                    gravityY = reader.readFloat32()
                    metersPerPixel = reader.readFloat32()

                    if (dw.isVersionAtLeast(2024, 13, 0, 0)) {
                        val icCount = reader.readInt32()
                        reader.skip(4 * icCount) // instanceCreationOrderIDs
                    }

                    var layersPtr = 0
                    if (dw.isVersionAtLeast(2, 0, 0, 0)) {
                        layersPtr = reader.readInt32()
                        if (dw.isVersionAtLeast(2, 3, 0, 0)) {
                            reader.skip(4) // sequencesPtr
                        }
                    }

                    // Backgrounds (always 8)
                    reader.position = backgroundsPtr
                    val bgPtrs = reader.readPointerTable()
                    for (j in bgPtrs.indices) {
                        if (8 > j) {
                            reader.position = bgPtrs[j]
                            backgrounds[j] = RoomBackground().apply {
                                enabled = reader.readBool32()
                                foreground = reader.readBool32()
                                backgroundDefinition = reader.readInt32()
                                x = reader.readInt32()
                                y = reader.readInt32()
                                tileX = reader.readInt32()
                                tileY = reader.readInt32()
                                speedX = reader.readInt32()
                                speedY = reader.readInt32()
                                stretch = reader.readBool32()
                            }
                        }
                    }

                    // Views (always 8)
                    reader.position = viewsPtr
                    val viewPtrs = reader.readPointerTable()
                    for (j in viewPtrs.indices) {
                        if (8 > j) {
                            reader.position = viewPtrs[j]
                            views[j] = RoomView().apply {
                                enabled = reader.readBool32()
                                viewX = reader.readInt32()
                                viewY = reader.readInt32()
                                viewWidth = reader.readInt32()
                                viewHeight = reader.readInt32()
                                portX = reader.readInt32()
                                portY = reader.readInt32()
                                portWidth = reader.readInt32()
                                portHeight = reader.readInt32()
                                borderX = reader.readInt32()
                                borderY = reader.readInt32()
                                speedX = reader.readInt32()
                                speedY = reader.readInt32()
                                objectId = reader.readInt32()
                            }
                        }
                    }

                    // Game Objects
                    reader.position = gameObjectsPtr
                    val objPtrs = reader.readPointerTable()
                    gameObjects = objPtrs.map { objPtr ->
                        reader.position = objPtr
                        RoomGameObject().apply {
                            x = reader.readInt32()
                            y = reader.readInt32()
                            objectDefinition = reader.readInt32()
                            instanceID = reader.readInt32()
                            creationCode = reader.readInt32()
                            scaleX = reader.readFloat32()
                            scaleY = reader.readFloat32()
                            if (dw.isVersionAtLeast(2, 2, 2, 302)) {
                                imageSpeed = reader.readFloat32()
                                imageIndex = reader.readInt32()
                            }
                            color = reader.readInt32()
                            rotation = reader.readFloat32()
                            if (dw.gen8.bytecodeVersion >= 16) {
                                preCreateCode = reader.readInt32()
                            }
                        }
                    }

                    // Tiles
                    reader.position = tilesPtr
                    val tilePtrs = reader.readPointerTable()
                    tiles = tilePtrs.map { tilePtr ->
                        reader.position = tilePtr
                        readRoomTile(reader, dw)
                    }

                    // GMS2 layers
                    if (dw.isVersionAtLeast(2, 0, 0, 0) && layersPtr != 0) {
                        reader.position = layersPtr
                        val layerPtrs = reader.readPointerTable()
                        layers = layerPtrs.map { layerPtr ->
                            reader.position = layerPtr
                            RoomLayer().apply {
                                name = reader.readStringPtr()
                                id = reader.readInt32()
                                type = reader.readInt32()
                                depth = reader.readInt32()
                                xOffset = reader.readFloat32()
                                yOffset = reader.readFloat32()
                                hSpeed = reader.readFloat32()
                                vSpeed = reader.readFloat32()
                                visible = reader.readBool32()

                                if (dw.isVersionAtLeast(2022, 1, 0, 0)) {
                                    // EffectEnabled (bool32), EffectType (string ptr), EffectProperties (SimpleList<EffectProperty>)
                                    reader.skip(4) // EffectEnabled
                                    reader.skip(4) // EffectType (string ptr)
                                    val effectPropCount = reader.readInt32()
                                    // Each EffectProperty is 12 bytes: Kind(int32) + Name(ptr) + Value(ptr)
                                    reader.skip(effectPropCount * 12)
                                }

                                when (type) {
                                    RoomLayerType.PATH, RoomLayerType.PATH2 -> { /* nothing */ }
                                    RoomLayerType.EFFECT -> {
                                        // In GMS 2022.1+, Effect layer data is empty (fields moved to layer header).
                                        if (!dw.isVersionAtLeast(2022, 1, 0, 0)) {
                                            reader.skip(4) // EffectType (string ptr)
                                            val propCount = reader.readInt32()
                                            reader.skip(propCount * 12)
                                        }
                                    }
                                    RoomLayerType.ASSETS -> {
                                        val legacyTilesPtr = reader.readInt32()
                                        val spritesPtr = reader.readInt32()

                                        reader.position = legacyTilesPtr
                                        val assetTilePtrs = reader.readPointerTable()
                                        val assetTiles = assetTilePtrs.map { tp ->
                                            reader.position = tp
                                            readRoomTile(reader, dw)
                                        }

                                        reader.position = spritesPtr
                                        val spritePtrs = reader.readPointerTable()
                                        val spriteInstances = spritePtrs.map { sp ->
                                            reader.position = sp
                                            SpriteInstance().apply {
                                                name = reader.readStringPtr()
                                                spriteIndex = reader.readInt32()
                                                x = reader.readInt32()
                                                y = reader.readInt32()
                                                scaleX = reader.readFloat32()
                                                scaleY = reader.readFloat32()
                                                color = reader.readInt32()
                                                animationSpeed = reader.readFloat32()
                                                animationSpeedType = reader.readInt32()
                                                frameIndex = reader.readFloat32()
                                                rotation = reader.readFloat32()
                                            }
                                        }

                                        assetsData = RoomLayerAssetsData().apply {
                                            legacyTiles = assetTiles
                                            sprites = spriteInstances
                                        }
                                    }
                                    RoomLayerType.BACKGROUND -> {
                                        backgroundData = RoomLayerBackgroundData().apply {
                                            visible = reader.readBool32()
                                            foreground = reader.readBool32()
                                            spriteIndex = reader.readInt32()
                                            hTiled = reader.readBool32()
                                            vTiled = reader.readBool32()
                                            stretch = reader.readBool32()
                                            color = reader.readInt32()
                                            firstFrame = reader.readFloat32()
                                            animSpeed = reader.readFloat32()
                                            animSpeedType = reader.readInt32()
                                        }
                                    }
                                    RoomLayerType.INSTANCES -> {
                                        val instanceCount = reader.readInt32()
                                        instancesData = RoomLayerInstancesData().apply {
                                            instanceIds = IntArray(instanceCount) { reader.readInt32() }
                                        }
                                    }
                                    RoomLayerType.TILES -> {
                                        val bgIdx = reader.readInt32()
                                        val tx = reader.readInt32()
                                        val ty = reader.readInt32()
                                        val totalTiles = tx * ty
                                        tilesData = RoomLayerTilesData().apply {
                                            backgroundIndex = bgIdx
                                            tilesX = tx
                                            tilesY = ty
                                            tileData = IntArray(totalTiles) { reader.readInt32() }
                                        }
                                    }
                                    else -> error("Unsupported Room Layer Type $type")
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun readRoomTile(reader: BinaryReader, dw: DataWin): RoomTile {
            return RoomTile().apply {
                x = reader.readInt32()
                y = reader.readInt32()
                useSpriteDefinition = dw.isVersionAtLeast(2, 0, 0, 0)
                backgroundDefinition = reader.readInt32()
                sourceX = reader.readInt32()
                sourceY = reader.readInt32()
                width = reader.readInt32()
                height = reader.readInt32()
                tileDepth = reader.readInt32()
                instanceID = reader.readInt32()
                scaleX = reader.readFloat32()
                scaleY = reader.readFloat32()
                color = reader.readInt32()
            }
        }

        private fun parseTPAG(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.tpag.items = ptrs.mapIndexed { i, ptr ->
                reader.position = ptr
                TexturePageItem().apply {
                    sourceX = reader.readUint16()
                    sourceY = reader.readUint16()
                    sourceWidth = reader.readUint16()
                    sourceHeight = reader.readUint16()
                    targetX = reader.readUint16()
                    targetY = reader.readUint16()
                    targetWidth = reader.readUint16()
                    targetHeight = reader.readUint16()
                    boundingWidth = reader.readUint16()
                    boundingHeight = reader.readUint16()
                    texturePageId = reader.readInt16()
                }
            }
            // Build offset -> index map
            for (i in ptrs.indices) {
                dw.tpagOffsetMap[ptrs[i]] = i
            }
        }

        private fun parseCODE(reader: BinaryReader, dw: DataWin, chunkLength: Int, chunkDataStart: Int) {
            if (chunkLength == 0) return // YYC-compiled, no bytecode

            val codePtrs = reader.readPointerTable()
            val entries = codePtrs.map { ptr ->
                reader.position = ptr
                val name = reader.readStringPtr()
                val length = reader.readInt32()
                val localsCount = reader.readUint16()
                val argumentsCount = reader.readUint16()
                val relAddrFieldPos = reader.position
                val bytecodeRelAddr = reader.readInt32()
                val bytecodeAbsoluteOffset = relAddrFieldPos + bytecodeRelAddr
                val offset = reader.readInt32()
                CodeEntry(name, length, localsCount, argumentsCount, bytecodeAbsoluteOffset, offset)
            }
            dw.code.entries = entries

            // Load bytecode blob
            if (entries.isNotEmpty()) {
                val blobStart = entries.minOf { it.bytecodeAbsoluteOffset }
                val chunkEnd = chunkDataStart + chunkLength
                val blobSize = chunkEnd - blobStart
                dw.bytecodeBufferBase = blobStart
                dw.bytecodeBuffer = reader.readBytesAt(blobStart, blobSize)
            }
        }

        private fun parseVARI(reader: BinaryReader, dw: DataWin, chunkLength: Int) {
            val v = dw.vari
            v.varCount1 = reader.readInt32()
            v.varCount2 = reader.readInt32()
            v.maxLocalVarCount = reader.readInt32()
            val variableCount = (chunkLength - 12) / 20
            v.variables = List(variableCount) {
                Variable(reader.readStringPtr(), reader.readInt32(), reader.readInt32(), reader.readInt32(), reader.readInt32())
            }
        }

        private fun parseFUNC(reader: BinaryReader, dw: DataWin) {
            val f = dw.func
            val funcCount = reader.readInt32()
            f.functions = List(funcCount) {
                val name = reader.readStringPtr()
                val occurrences = reader.readInt32()
                var rawAddr = reader.readInt32()
                // In GMS 2.3+, firstAddress points to the operand word (instruction + 4), not the instruction itself
                if (dw.isVersionAtLeast(2, 3, 0, 0) && rawAddr != -1) {
                    rawAddr -= 4
                }
                Function(name, occurrences, rawAddr)
            }
            val codeLocalsCount = reader.readInt32()
            f.codeLocals = List(codeLocalsCount) {
                val localVarCount = reader.readInt32()
                val name = reader.readStringPtr()
                CodeLocals(name, List(localVarCount) { LocalVar(reader.readInt32(), reader.readStringPtr()) })
            }
        }

        private fun parseSTRG(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.strg.strings = ptrs.map { ptr ->
                val saved = reader.position
                reader.position = ptr // points to length prefix
                val length = reader.readInt32()
                val bytes = reader.readBytes(length)
                reader.position = saved
                bytes.decodeToString()
            }
        }

        private fun parseTXTR(reader: BinaryReader, dw: DataWin, chunkEnd: Int) {
            val ptrs = reader.readPointerTable()
            if (ptrs.isEmpty()) return

            val hasGeneratedMips = dw.isVersionAtLeast(2, 0, 0, 0)
            var has2022_3 = dw.isVersionAtLeast(2022, 3, 0, 0)
            var has2022_9 = dw.isVersionAtLeast(2022, 9, 0, 0)

            // Detect GMS 2022.3+ (TextureBlockSize) and 2022.9+ (Width/Height/IndexInGroup) by probing the distance between the first two entry pointers.
            // Layouts:
            //   pre-2022.3: scaled+generatedMips+blobOffset = 12 bytes
            //   2022.3+: ... + textureBlockSize = 16 bytes
            //   2022.9+: ... + width + height + indexInGroup = 28 bytes
            if (ptrs.size >= 2 && hasGeneratedMips && !has2022_9) {
                val diff = ptrs[1] - ptrs[0]
                if (diff == 28) {
                    dw.bumpVersionTo(2022, 9, 0, 0)
                    has2022_3 = true
                    has2022_9 = true
                } else if (diff == 16 && !has2022_3) {
                    dw.bumpVersionTo(2022, 3, 0, 0)
                    has2022_3 = true
                }
            }

            data class TexMeta(
                val scaled: Int,
                val generatedMips: Int,
                val textureBlockSize: Int,
                val textureWidth: Int,
                val textureHeight: Int,
                val indexInGroup: Int,
                val blobOffset: Int
            )
            val metas = ptrs.map { ptr ->
                reader.position = ptr
                val scaled = reader.readInt32()
                val generatedMips = if (hasGeneratedMips) reader.readInt32() else 0
                val textureBlockSize = if (has2022_3) reader.readInt32() else 0
                val textureWidth: Int
                val textureHeight: Int
                val indexInGroup: Int
                if (has2022_9) {
                    textureWidth = reader.readInt32()
                    textureHeight = reader.readInt32()
                    indexInGroup = reader.readInt32()
                } else {
                    textureWidth = 0
                    textureHeight = 0
                    indexInGroup = 0
                }
                val blobOffset = reader.readInt32()
                TexMeta(scaled, generatedMips, textureBlockSize, textureWidth, textureHeight, indexInGroup, blobOffset)
            }

            // Compute blob sizes from successive offsets
            dw.txtr.textures = metas.mapIndexed { i, meta ->
                if (meta.blobOffset == 0) {
                    Texture(meta.scaled, 0, 0, null, meta.generatedMips, meta.textureBlockSize, meta.textureWidth, meta.textureHeight, meta.indexInGroup)
                } else {
                    val blobSize = if (metas.size > i + 1 && metas[i + 1].blobOffset != 0) {
                        metas[i + 1].blobOffset - meta.blobOffset
                    } else {
                        chunkEnd - meta.blobOffset
                    }
                    val blobData = if (blobSize > 0) reader.readBytesAt(meta.blobOffset, blobSize) else null
                    Texture(meta.scaled, meta.blobOffset, blobSize, blobData, meta.generatedMips, meta.textureBlockSize, meta.textureWidth, meta.textureHeight, meta.indexInGroup)
                }
            }
        }

        private fun parseAUDO(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.audo.entries = ptrs.map { ptr ->
                reader.position = ptr
                val dataSize = reader.readInt32()
                val dataOffset = reader.position
                val data = if (dataSize > 0) reader.readBytes(dataSize) else null
                AudioEntry(dataSize, dataOffset, data)
            }
        }
    }
}

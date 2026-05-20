@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mrpowergamerbr.butterscotchpreprocessor.network

import js.buffer.ArrayBufferLike
import js.objects.unsafeJso
import js.typedarrays.Int8Array
import kotlin.js.JsAny

// All packet payloads travel as plain JS objects so structured cloning across the worker  boundary is zero-copy for byte arrays.
// The `type` field discriminates the subtype.

external interface C2SPacket : JsAny {
    var type: String
}

external interface C2SEnvelope : JsAny {
    var id: Int
    var packet: C2SPacket
}

external interface C2SDecodeAudioPacket : C2SPacket {
    var data: Int8Array<ArrayBufferLike>
}

external interface C2SProcessDataWin : C2SPacket {
    var data: Int8Array<ArrayBufferLike>
    // Records keyed by name -> Int8Array of bytes. Typed as JsAny because we
    // iterate keys via Object.keys at the consumer.
    var externalAudioFiles: JsAny
    var audioGroupFiles: JsAny
    var musFiles: JsAny
    var force4bppPatterns: Array<String>
    var atlasSize: Int
    // "ps2" (default, backcompat) or "nspire". Worker branches the pipeline.
    var target: String
}

object C2SPacketType {
    const val DECODE_AUDIO = "c2sDecodeAudio"
    const val PROCESS_DATA_WIN = "c2sProcessDataWin"
}

fun c2sEnvelope(id: Int, packet: C2SPacket): C2SEnvelope = unsafeJso {
    this.id = id
    this.packet = packet
}

fun c2sDecodeAudioPacket(data: ByteArray): C2SDecodeAudioPacket = unsafeJso {
    type = C2SPacketType.DECODE_AUDIO
    this.data = data.unsafeCast<Int8Array<ArrayBufferLike>>()
}

fun c2sProcessDataWin(
    data: ByteArray,
    externalAudioFiles: Map<String, ByteArray>,
    audioGroupFiles: Map<Int, ByteArray>,
    musFiles: Map<String, ByteArray>,
    force4bppPatterns: List<String>,
    atlasSize: Int,
    target: String = "ps2",
): C2SProcessDataWin = unsafeJso {
    type = C2SPacketType.PROCESS_DATA_WIN
    this.data = data.unsafeCast<Int8Array<ArrayBufferLike>>()
    this.externalAudioFiles = byteArrayMapToJsObject(externalAudioFiles)
    this.audioGroupFiles = byteArrayMapToJsObject(audioGroupFiles.mapKeys { it.key.toString() })
    this.musFiles = byteArrayMapToJsObject(musFiles)
    this.force4bppPatterns = force4bppPatterns.toTypedArray()
    this.atlasSize = atlasSize
    this.target = target
}

internal fun byteArrayMapToJsObject(map: Map<String, ByteArray>): JsAny {
    val obj = unsafeJso<JsAny> {}
    val dyn = obj.asDynamic()
    for ((k, v) in map) dyn[k] = v.unsafeCast<Int8Array<ArrayBufferLike>>()
    return obj
}

internal fun jsObjectToByteArrayMap(jsObj: JsAny): Map<String, ByteArray> {
    val keys = js("Object.keys(jsObj)").unsafeCast<Array<String>>()
    val out = HashMap<String, ByteArray>(keys.size)
    val dyn = jsObj.asDynamic()
    for (k in keys) out[k] = dyn[k].unsafeCast<ByteArray>()
    return out
}

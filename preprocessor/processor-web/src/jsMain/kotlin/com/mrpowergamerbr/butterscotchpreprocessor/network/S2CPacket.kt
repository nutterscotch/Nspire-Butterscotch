@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mrpowergamerbr.butterscotchpreprocessor.network

import com.mrpowergamerbr.butterscotchpreprocessor.AudioData
import js.buffer.ArrayBufferLike
import js.objects.unsafeJso
import js.typedarrays.Int8Array
import kotlin.js.JsAny

external interface S2CPacket : JsAny {
    var type: String
}

external interface S2CEnvelope : JsAny {
    var id: Int
    var packet: S2CPacket
    var terminal: Boolean
}

external interface AudioDataWire : JsAny {
    var format: Int
    var sampleRate: Int
    var channels: Int
    var bitsPerSample: Int
    var data: Int8Array<ArrayBufferLike>
    var sampleCount: Int
}

external interface S2CDecodeAudioPacket : S2CPacket {
    // null when the worker failed to decode (not a WAV/OGG)
    var audio: AudioDataWire?
}

external interface S2CProgressPacket : S2CPacket {
    var message: String
}

external interface S2CErrorPacket : S2CPacket {
    var message: String
}

external interface S2CProcessResultPacket : S2CPacket {
    var gameName: String
    var clut4Bin: Int8Array<ArrayBufferLike>
    var clut8Bin: Int8Array<ArrayBufferLike>
    var texturesBin: Int8Array<ArrayBufferLike>
    var atlasBin: Int8Array<ArrayBufferLike>
    var soundBnkBin: Int8Array<ArrayBufferLike>
    var soundsBin: Int8Array<ArrayBufferLike>
    // Nspire-only: slim data.win (TXTR/AUDO stripped). null/absent for PS2.
    var slimDataWin: Int8Array<ArrayBufferLike>?
}

object S2CPacketType {
    const val DECODE_AUDIO = "s2cDecodeAudio"
    const val PROGRESS = "s2cProgress"
    const val ERROR = "s2cError"
    const val PROCESS_RESULT = "s2cProcessResult"
}

fun s2cEnvelope(id: Int, packet: S2CPacket, terminal: Boolean): S2CEnvelope = unsafeJso {
    this.id = id
    this.packet = packet
    this.terminal = terminal
}

fun audioDataWire(audio: AudioData): AudioDataWire = unsafeJso {
    format = audio.format
    sampleRate = audio.sampleRate
    channels = audio.channels
    bitsPerSample = audio.bitsPerSample
    data = audio.data.unsafeCast<Int8Array<ArrayBufferLike>>()
    sampleCount = audio.sampleCount
}

fun AudioDataWire.toAudioData(): AudioData = AudioData(
    format = format,
    sampleRate = sampleRate,
    channels = channels,
    bitsPerSample = bitsPerSample,
    data = data.unsafeCast<ByteArray>(),
    sampleCount = sampleCount,
)

fun s2cDecodeAudioPacket(audio: AudioData?): S2CDecodeAudioPacket = unsafeJso {
    type = S2CPacketType.DECODE_AUDIO
    this.audio = audio?.let { audioDataWire(it) }
}

fun s2cProgressPacket(message: String): S2CProgressPacket = unsafeJso {
    type = S2CPacketType.PROGRESS
    this.message = message
}

fun s2cErrorPacket(message: String): S2CErrorPacket = unsafeJso {
    type = S2CPacketType.ERROR
    this.message = message
}

fun s2cProcessResultPacket(
    gameName: String,
    clut4Bin: ByteArray,
    clut8Bin: ByteArray,
    texturesBin: ByteArray,
    atlasBin: ByteArray,
    soundBnkBin: ByteArray,
    soundsBin: ByteArray,
    slimDataWin: ByteArray? = null,
): S2CProcessResultPacket = unsafeJso {
    type = S2CPacketType.PROCESS_RESULT
    this.gameName = gameName
    this.clut4Bin = clut4Bin.unsafeCast<Int8Array<ArrayBufferLike>>()
    this.clut8Bin = clut8Bin.unsafeCast<Int8Array<ArrayBufferLike>>()
    this.texturesBin = texturesBin.unsafeCast<Int8Array<ArrayBufferLike>>()
    this.atlasBin = atlasBin.unsafeCast<Int8Array<ArrayBufferLike>>()
    this.soundBnkBin = soundBnkBin.unsafeCast<Int8Array<ArrayBufferLike>>()
    this.soundsBin = soundsBin.unsafeCast<Int8Array<ArrayBufferLike>>()
    this.slimDataWin = slimDataWin?.unsafeCast<Int8Array<ArrayBufferLike>>()
}

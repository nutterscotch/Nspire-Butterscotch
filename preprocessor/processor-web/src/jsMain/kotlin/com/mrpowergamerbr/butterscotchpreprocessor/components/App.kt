package com.mrpowergamerbr.butterscotchpreprocessor.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mrpowergamerbr.butterscotchpreprocessor.ButterWorkerClient
import com.mrpowergamerbr.butterscotchpreprocessor.ButterscotchPreprocessorWeb
import com.mrpowergamerbr.butterscotchpreprocessor.DataWin
import com.mrpowergamerbr.butterscotchpreprocessor.DataWinParserOptions
import com.mrpowergamerbr.butterscotchpreprocessor.StoreZipBuilder
import com.mrpowergamerbr.butterscotchpreprocessor.network.S2CErrorPacket
import com.mrpowergamerbr.butterscotchpreprocessor.network.S2CPacketType
import com.mrpowergamerbr.butterscotchpreprocessor.network.S2CProcessResultPacket
import com.mrpowergamerbr.butterscotchpreprocessor.network.S2CProgressPacket
import com.mrpowergamerbr.butterscotchpreprocessor.network.c2sProcessDataWin
import js.buffer.ArrayBuffer
import js.date.Date
import js.typedarrays.Uint8Array
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import web.blob.Blob
import web.blob.BlobPropertyBag
import web.events.EventHandler
import web.file.FileReader
import web.url.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun App(m: ButterscotchPreprocessorWeb) {
    // `m` is no longer needed inside the Nspire-only flow (the color picker that
    // used it is gone with the PS2 UI) but stays in the signature so the launcher
    // call site doesn't need to change.
    @Suppress("UNUSED_PARAMETER") m

    var status by remember { mutableStateOf("Select the game's folder to begin!") }
    var parsedDataWin by remember { mutableStateOf<DataWin?>(null) }
    val logMessages = remember { mutableStateListOf<String>() }
    var downloadUrl by remember { mutableStateOf<String?>(null) }
    var downloadFileName by remember { mutableStateOf("butterscotch-nspire.zip") }
    var processing by remember { mutableStateOf(false) }
    var loadedFileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var parsedGameName by remember { mutableStateOf<String?>(null) }
    val force4bppPatterns = remember { mutableStateSetOf<String>() }
    var atlasSize by remember { mutableStateOf(1024) }
    var atlasSizeText by remember { mutableStateOf("1024") }
    // Debug mode: written into the ZIP as config.tns. The runner reads it to gate
    // the stats overlay AND the debug hotkeys together (PS2's debugOverlayEnabled
    // analog). Off by default so released games ship clean.
    var debugMode by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf(0.0) }

    val scope = rememberCoroutineScope()
    val workerClient = remember { ButterWorkerClient() }

    // Bundle the worker's five cooked outputs into a STORE-only ZIP and offer
    // it as a download — the calculator just expects a folder of these files
    // alongside engine.tns. No ISO, no PS2 metadata.
    suspend fun handleNspireResult(result: S2CProcessResultPacket) {
        try {
            status = "Building ZIP..."
            val slim = result.slimDataWin?.unsafeCast<ByteArray>()
                ?: error("Worker did not return a slim data.win")
            val clut4 = result.clut4Bin.unsafeCast<ByteArray>()
            val clut8 = result.clut8Bin.unsafeCast<ByteArray>()
            val textures = result.texturesBin.unsafeCast<ByteArray>()
            val atlas = result.atlasBin.unsafeCast<ByteArray>()

            // Everything lives under a top-level bs/ folder so the archive extracts
            // into a correctly-named directory: the runner hard-codes /documents/bs/
            // as its asset + save path, so the user just drops this bs/ folder into
            // My Documents — no renaming, nothing to get wrong.
            val dir = "bs/"
            val zip = StoreZipBuilder()
            zip.add("${dir}data.win.tns", slim)
            zip.add("${dir}ATLAS.BIN.tns", atlas)
            zip.add("${dir}CLUT4.BIN.tns", clut4)
            zip.add("${dir}CLUT8.BIN.tns", clut8)
            zip.add("${dir}TEXTURES.BIN.tns", textures)

            // Runtime config the runner reads at startup. Currently just the debug
            // toggle (stats overlay + debug hotkeys). JSON so more keys can be added
            // later without changing the file contract. Named .tns so it survives
            // the calculator's documents filter like the other assets.
            val configJson = """{"debugMode": $debugMode}"""
            zip.add("${dir}config.tns", configJson.encodeToByteArray())

            // Bundle the runner itself so the download is a complete, ready-to-run
            // package. Served by the backend at /assets/engine.tns. If it isn't
            // available (e.g. a deployment built without a packaged runner), don't
            // fail the cook — produce the data-only ZIP and warn loudly so the user
            // knows they still need to supply engine.tns themselves.
            var bundledRunner = false
            try {
                val engine = fetchBinary("/assets/engine.tns")
                zip.add("${dir}engine.tns", engine)
                bundledRunner = true
            } catch (e: Exception) {
                logMessages.add("WARNING: could not bundle engine.tns (${e.message}). " +
                        "ZIP contains data only — add engine.tns to the folder yourself.")
            }

            val zipBytes = zip.build()

            val safeName = (parsedGameName ?: "butterscotch")
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                .take(48)
                .ifBlank { "butterscotch" }
            downloadFileName = "${safeName}-nspire.zip"
            val blob = Blob(arrayOf(zipBytes), BlobPropertyBag(type = "application/zip"))
            downloadUrl = URL.createObjectURL(blob)
            val runnerNote = if (bundledRunner) " (runner included)" else " (runner NOT included — see log)"
            status = "Done!$runnerNote Took ${Date.now() - startTime}ms"
        } catch (e: Exception) {
            status = "Error creating ZIP: ${e.message}"
            logMessages.add("Error: ${e.stackTraceToString()}")
        } finally {
            processing = false
        }
    }

    H2 { Text("Converter") }

    if (!processing) {
        Input(type = InputType.File) {
            attr("webkitdirectory", "")
            onChange { event ->
                parsedDataWin = null

                val input: dynamic = event.target
                val files: dynamic = input.files
                if (files == null || files.length == 0) return@onChange

                downloadUrl = null
                logMessages.clear()
                parsedGameName = null
                status = "Reading folder..."

                scope.launch {
                    try {
                        // The Nspire cook only needs data.win — audio, audiogroup
                        // dat files, and other sources are ignored (audio is dropped).
                        var dataWinFile: dynamic = null
                        val fileCount = files.length as Int
                        for (i in 0 until fileCount) {
                            val file: dynamic = files[i]
                            val name = (file.name as String).lowercase()
                            if (name.endsWith(".win") || name.endsWith(".unx") || name.endsWith(".osx")) {
                                dataWinFile = file
                            }
                        }

                        if (dataWinFile == null) {
                            status = "No data.win file found in the selected folder!"
                            return@launch
                        }

                        status = "Reading data.win..."
                        val bytes = readFileAsBytes(dataWinFile)
                        loadedFileBytes = bytes

                        // Quick gen8-only parse just to pull the game's display name
                        // for the ZIP filename + bytecode version readout.
                        val dw = DataWin.parse(bytes, DataWinParserOptions(
                            parseGen8 = true,
                            parseOptn = false,
                            parseLang = false,
                            parseExtn = false,
                            parseSond = false,
                            parseAgrp = false,
                            parseSprt = false,
                            parseBgnd = false,
                            parsePath = false,
                            parseScpt = false,
                            parseGlob = false,
                            parseShdr = false,
                            parseFont = false,
                            parseTmln = false,
                            parseObjt = false,
                            parseRoom = false,
                            parseTpag = false,
                            parseCode = false,
                            parseVari = false,
                            parseFunc = false,
                            parseStrg = true,
                            parseTxtr = false,
                            parseAudo = false,
                        ))

                        val gameName = dw.gen8.displayName ?: dw.gen8.name ?: "Unknown"
                        parsedGameName = gameName
                        status = "Game: $gameName [Bytecode Version ${dw.gen8.bytecodeVersion}]"
                        parsedDataWin = dw
                    } catch (e: Exception) {
                        status = "Error reading folder: ${e.message}"
                        loadedFileBytes = null
                    }
                }
            }
        }
    }

    P({ classes("status-text") }) { Text(status) }

    if (parsedGameName != null && !processing && downloadUrl == null) {
        P {
            Text("Output is a ready-to-run ZIP containing a single bs/ folder (the runner engine.tns plus the cooked data.win.tns + ATLAS/CLUT4/CLUT8/TEXTURES.BIN.tns). Extract it and copy the whole bs folder into My Documents on the calculator, then run bs/engine.tns. Audio is stripped (silent); sprites/backgrounds/tiles are 2× box-downsampled to fit 320×240; fonts stay full-res.")
        }

        FieldWrappers {
            StringSetTable(
                "Force 4bpp Images (regex, must match the full image name, example: spr_test.*)",
                "Image Name Regex",
                force4bppPatterns
            )

            FieldWrapper {
                FieldInformation {
                    Div(attrs = { classes("field-title") }) { Text("Texture Atlas Size") }
                    Div(attrs = { classes("field-description") }) {
                        Text("Width/height of each atlas page in pixels. Use 1024 for Nspire. lower sizes fucks up fonts in untdertale")
                    }
                }

                TextInput(atlasSizeText) {
                    placeholder("1024")
                    onInput {
                        val raw = it.value.filter { c -> c.isDigit() }.take(5)
                        atlasSizeText = raw
                        val parsed = raw.toIntOrNull()
                        if (parsed != null && parsed > 0) {
                            atlasSize = parsed
                        }
                    }
                }
            }

            DiscordToggle(
                id = "debug-mode",
                title = "Debug Mode",
                description = "When on, the in-game stats overlay and debug hotkeys " +
                        "(pause, room warp, uncap FPS, global-reset) are active. " +
                        "leave it off if you dont know what youre doing",
                checked = debugMode,
                onChange = { debugMode = it }
            )
        }

        Div({ classes("buttons-wrapper") }) {
            Button({
                classes("discord-button", "primary")
                onClick {
                    val bytes = loadedFileBytes ?: return@onClick
                    processing = true
                    startTime = Date.now()
                    logMessages.clear()
                    status = "Processing..."

                    scope.launch {
                        try {
                            val response = workerClient.sendPacket(
                                c2sProcessDataWin(
                                    bytes,
                                    emptyMap(),
                                    emptyMap(),
                                    emptyMap(),
                                    force4bppPatterns.toList(),
                                    atlasSize,
                                    "nspire",
                                ),
                                onEvent = { event ->
                                    if (event.type == S2CPacketType.PROGRESS) {
                                        val progress = event.unsafeCast<S2CProgressPacket>()
                                        println(progress.message)
                                        logMessages.add(progress.message)
                                    }
                                }
                            )

                            when (response.type) {
                                S2CPacketType.PROCESS_RESULT ->
                                    handleNspireResult(response.unsafeCast<S2CProcessResultPacket>())
                                S2CPacketType.ERROR -> {
                                    val err = response.unsafeCast<S2CErrorPacket>()
                                    status = "Error: ${err.message}"
                                    processing = false
                                }
                                else -> {
                                    status = "Error: unexpected response ${response.type}"
                                    processing = false
                                }
                            }
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                            processing = false
                        }
                    }
                }
            }) {
                Text("Generate TI-Nspire ZIP")
            }
        }
    }

    if (logMessages.isNotEmpty()) {
        Div({ classes("progress-log") }) {
            for (msg in logMessages) {
                Div({ classes("log-entry") }) { Text(msg) }
            }
        }
    }

    if (downloadUrl != null) {
        Div({ classes("buttons-wrapper") }) {
            A(href = downloadUrl, attrs = {
                attr("download", downloadFileName)
                classes("discord-button", "success")
            }) {
                Text("Download ZIP")
            }

            DiscordButton(
                DiscordButtonType.NO_BACKGROUND_THEME_DEPENDENT_DARK_TEXT,
                attrs = {
                    onClick {
                        downloadUrl = null
                        logMessages.clear()
                    }
                }
            ) {
                Text("Go Back")
            }
        }
    }
}

// Fetch a binary URL (the bundled runner) into a ByteArray. Uses XMLHttpRequest
// via dynamic to avoid pinning a specific kotlin-wrappers fetch API — mirrors the
// dynamic/FileReader style already used in readFileAsBytes below. Resolves only on
// a 2xx response; any other status or a network error rejects so the caller can
// fall back to a runner-less ZIP.
private suspend fun fetchBinary(url: String): ByteArray {
    return suspendCancellableCoroutine { cont ->
        val xhr: dynamic = js("new XMLHttpRequest()")
        xhr.open("GET", url)
        xhr.responseType = "arraybuffer"
        xhr.onload = {
            val statusCode = (xhr.status as Int)
            if (statusCode in 200..299) {
                val arrayBuffer = xhr.response as ArrayBuffer
                val uint8Array = Uint8Array(arrayBuffer)
                val length = uint8Array.length as Int
                val bytes = ByteArray(length)
                for (i in 0 until length) {
                    bytes[i] = uint8Array[i].toByte()
                }
                cont.resume(bytes)
            } else {
                cont.resumeWithException(RuntimeException("HTTP $statusCode fetching $url"))
            }
            Unit
        }
        xhr.onerror = {
            cont.resumeWithException(RuntimeException("network error fetching $url"))
            Unit
        }
        xhr.send()
    }
}

private suspend fun readFileAsBytes(file: dynamic): ByteArray {
    return suspendCancellableCoroutine { cont ->
        val reader = FileReader()
        reader.onload = EventHandler {
            val arrayBuffer = reader.result as ArrayBuffer
            val uint8Array = Uint8Array(arrayBuffer)
            val length = uint8Array.length as Int
            val bytes = ByteArray(length)
            for (i in 0 until length) {
                bytes[i] = uint8Array[i].toByte()
            }
            cont.resume(bytes)
        }
        reader.onerror = EventHandler {
            cont.resumeWithException(RuntimeException("Failed to read file"))
        }
        reader.readAsArrayBuffer(file)
    }
}

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mrpowergamerbr.butterscotchpreprocessor

import com.mrpowergamerbr.butterscotchpreprocessor.network.C2SPacket
import com.mrpowergamerbr.butterscotchpreprocessor.network.S2CEnvelope
import com.mrpowergamerbr.butterscotchpreprocessor.network.S2CPacket
import com.mrpowergamerbr.butterscotchpreprocessor.network.c2sEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlin.js.JsAny
import web.events.EventHandler
import web.location.location
import web.workers.Worker

class ButterWorkerClient {
    val isWorker = js("typeof document === 'undefined'") as Boolean

    private var nextId = 0
    private val pending = mutableMapOf<Int, PendingRequest>()
    val worker = if (isWorker) {
        Worker(location.href)
    } else {
        Worker("/assets/js/processor-web.js?v=${js("window.jsBundleHash") as String}")
    }

    private class PendingRequest(
        val deferred: CompletableDeferred<S2CPacket>,
        val onEvent: (S2CPacket) -> Unit,
    )

    init {
        worker.onmessage = EventHandler { event ->
            val envelope = event.data.unsafeCast<S2CEnvelope>()
            val request = pending[envelope.id] ?: return@EventHandler
            if (envelope.terminal) {
                pending.remove(envelope.id)
                request.deferred.complete(envelope.packet)
            } else {
                request.onEvent(envelope.packet)
            }
        }
    }

    suspend fun sendPacket(
        request: C2SPacket,
        onEvent: (S2CPacket) -> Unit = {},
    ): S2CPacket {
        val id = nextId++
        val deferred = CompletableDeferred<S2CPacket>()
        pending[id] = PendingRequest(deferred, onEvent)
        worker.postMessage(c2sEnvelope(id, request).unsafeCast<JsAny>())
        return deferred.await()
    }

    fun terminate() {
        worker.terminate()
    }
}

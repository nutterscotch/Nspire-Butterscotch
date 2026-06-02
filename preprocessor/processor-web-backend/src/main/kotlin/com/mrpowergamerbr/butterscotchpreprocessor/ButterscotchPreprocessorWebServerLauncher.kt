package com.mrpowergamerbr.butterscotchpreprocessor

object ButterscotchPreprocessorWebServerLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        val jsBundle = ButterscotchPreprocessorWebServer::class.java.getResourceAsStream("/web/js/processor-web.js")!!
            .readBytes()
            .toString(Charsets.UTF_8)

        val cssBundle = ButterscotchPreprocessorWebServer::class.java.getResourceAsStream("/web/css/style.css")!!
            .readBytes()
            .toString(Charsets.UTF_8)

        // The TI-Nspire runner that gets bundled into each generated ZIP. Optional:
        // if it wasn't copied into resources at build time (e.g. a dev build without
        // a freshly built engine.tns), the server skips the route and the frontend
        // falls back to a ZIP without the runner plus a clear warning.
        val engineTns = ButterscotchPreprocessorWebServer::class.java.getResourceAsStream("/web/engine.tns")
            ?.readBytes()
        if (engineTns == null) {
            System.err.println("WARN: /web/engine.tns not found on classpath — generated ZIPs will NOT include the runner")
        }

        val server = ButterscotchPreprocessorWebServer(jsBundle, cssBundle, engineTns)
        server.start()
    }
}

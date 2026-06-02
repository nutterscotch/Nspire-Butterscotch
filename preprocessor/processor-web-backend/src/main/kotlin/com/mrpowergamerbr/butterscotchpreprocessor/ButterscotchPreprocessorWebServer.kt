package com.mrpowergamerbr.butterscotchpreprocessor

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.html.consumers.delayed
import kotlinx.html.consumers.onFinalizeMap
import kotlinx.html.stream.HTMLStreamBuilder
import java.security.MessageDigest

class ButterscotchPreprocessorWebServer(
    private val jsBundle: String,
    private val cssBundle: String,
    // The TI-Nspire runner served at /assets/engine.tns and bundled into the ZIP
    // by the frontend. Null when no engine.tns was packaged at build time.
    private val engineTns: ByteArray? = null,
) {
    private val jsBundleHash = md5Hex(jsBundle.toByteArray())
    private val cssBundleHash = md5Hex(cssBundle.toByteArray())

    fun start() {
        val server = embeddedServer(Netty, port = 8080) {
            install(Compression) {
                gzip()
            }

            routing {
                get("/") {
                    call.respondHtml {
                        head {
                            meta(charset = "UTF-8")
                            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
                            title("Butterscotch Preprocessor")
                            link(rel = "stylesheet", href = "/assets/css/style.css?v=$cssBundleHash")
                        }
                        body {
                            div {
                                classes = setOf("sync-with-system-theme")
                                id = "app-wrapper"

                                div {
                                    id = "content-wrapper"

                                    h1 {
                                        text("Butterscotch (TI-Nspire)")
                                    }
                                    p {
                                        text("Butterscotch is an open source re-implementation of GameMaker: Studio's runner.")
                                    }
                                    p {
                                        text("Generate a TI-Nspire CX II package (ZIP of .tns files) for a GameMaker: Studio game. Tested with Undertale and DELTARUNE.")
                                    }

                                    p {
                                        b {
                                            text("Upstream Butterscotch Project URL: ")
                                        }
                                        a(href = "https://github.com/MrPowerGamerBR/Butterscotch") {
                                            +"https://github.com/MrPowerGamerBR/Butterscotch"
                                        }
                                    }


                                    h2 {
                                        text("Tested Hardware")
                                    }

                                    ul {
                                        li {
                                            text("TI-Nspire CX II / CX II CAS (primary target, color)")
                                        }
                                        li {
                                            text("MAYBE Nspire CX/CXCAS")
                                        }
                                    }
                                    b {
                                        text(
                                            if (engineTns != null)
                                                "Requires Ndless. The runner (engine.tns) is included. Extract the ZIP and copy the bs folder into My Documents on the calculator, then run bs/engine.tns."
                                            else
                                                "Requires Ndless. Extract the ZIP and copy the bs folder into My Documents on the calculator (add engine.tns to it), then run bs/engine.tns."
                                        )
                                    }

                                    hr {}

                                    // Compose HTML mounts here
                                    div {
                                        id = "root"
                                    }
                                }
                            }
                            script {
                                unsafe {
                                    raw("""window.jsBundleHash = "$jsBundleHash";""")
                                }
                            }
                            script(src = "/assets/js/processor-web.js?v=$jsBundleHash") {}
                        }
                    }
                }

                get("/assets/js/processor-web.js") {
                    call.respondText(
                        jsBundle,
                        contentType = ContentType.Application.JavaScript
                    )
                }

                get("/assets/css/style.css") {
                    call.respondText(
                        cssBundle,
                        contentType = ContentType.Text.CSS
                    )
                }

                // The TI-Nspire runner. The frontend fetches this and bundles it
                // into the generated ZIP so the download is complete and ready to
                // run. 404 when no engine.tns was packaged at build time — the
                // frontend then falls back to a runner-less ZIP with a warning.
                get("/assets/engine.tns") {
                    val bytes = engineTns
                    if (bytes == null) {
                        call.respond(HttpStatusCode.NotFound, "engine.tns not bundled")
                    } else {
                        call.respondBytes(bytes, ContentType.Application.OctetStream)
                    }
                }

            }
        }

        server.start(wait = true)
    }
}

private fun md5Hex(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

private const val AVERAGE_PAGE_SIZE = 32768

suspend fun ApplicationCall.respondHtml(status: HttpStatusCode? = null, content: HTML.() -> (Unit)) {
    val output = StringBuilder(AVERAGE_PAGE_SIZE)
    output.append("<!doctype html>")

    val builder = HTMLStreamBuilder(
        output,
        prettyPrint = false,
        xhtmlCompatible = false
    ).onFinalizeMap { sb, _ -> sb.toString() }.delayed()

    builder.html {
        content.invoke(this)
    }

    this.respondText(
        output.toString(),
        ContentType.Text.Html,
        status = status
    )
}
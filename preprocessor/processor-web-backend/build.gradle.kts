plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jib)
    application
}

application {
    mainClass.set("com.mrpowergamerbr.butterscotchpreprocessor.ButterscotchPreprocessorWebServerLauncher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val frontendJsBundle = tasks.getByPath(":processor-web:jsBrowserProductionWebpack")

val sassStyle = tasks.register<SassTask>("sassStyleScss") {
    this.inputSass.set(file("src/main/sass/style.scss"))
    this.inputSassFolder.set(file("src/main/sass/"))
    this.outputSass.set(file("$buildDir/sass/style-scss"))
}

tasks {
    processResources {
        dependsOn(frontendJsBundle)

        // Copy the JS bundle output to the backend resources
        from(frontendJsBundle) {
            into("web/js/")
        }

        // Copy the compiled CSS
        from(sassStyle) {
            into("web/css/")
        }

        // Copy butterscotch.elf from the frontend resources
        from(project(":processor-web").file("src/jsMain/resources/butterscotch.elf")) {
            into("web/")
        }

        // Copy the TI-Nspire runner (engine.tns) so the backend can serve it at
        // /assets/engine.tns and the frontend can bundle it straight into the
        // generated ZIP. The download is then a complete, ready-to-run package
        // (engine.tns + cooked .tns data) instead of requiring the user to source
        // the runner separately. (Same direct-from-source-resources copy pattern
        // as butterscotch.elf above; a missing file is silently skipped by Gradle.)
        from(project(":processor-web").file("src/jsMain/resources/engine.tns")) {
            into("web/")
        }
    }
}

// Assemble a fully STATIC version of the site into build/static-site/ for free
// static hosts (Cloudflare Pages, Netlify, GitHub Pages, ...). The whole cook runs
// client-side in a Web Worker — the backend only ever served static files — so this
// bundles those same files (JS, CSS, engine.tns) plus a prebuilt index.html. No JVM,
// no server, no cold starts. Run: ./gradlew :processor-web-backend:staticSite
tasks.register<Copy>("staticSite") {
    group = "distribution"
    description = "Builds a static, server-less version of the site into build/static-site/"
    dependsOn(frontendJsBundle, sassStyle)

    val outDir = layout.buildDirectory.dir("static-site")
    into(outDir)

    // Same /assets/ layout the absolute URLs in the page + worker expect.
    from(frontendJsBundle) { into("assets/js") }
    from(sassStyle) { into("assets/css") }
    from(project(":processor-web").file("src/jsMain/resources/engine.tns")) { into("assets") }
    from("src/main/static") // index.html at the site root

    doLast {
        logger.lifecycle("Static site assembled at: ${outDir.get().asFile}")
        logger.lifecycle("Deploy that folder to any static host (it serves from /).")
    }
}

jib {
    container {
        mainClass = "com.mrpowergamerbr.butterscotchpreprocessor.ButterscotchPreprocessorWebServerLauncher"
        ports = listOf("8080")
    }

    to {
        image = "ghcr.io/mrpowergamerbr/butterscotchpreprocessor"

        auth {
            username = System.getProperty("DOCKER_USERNAME") ?: System.getenv("DOCKER_USERNAME")
            password = System.getProperty("DOCKER_PASSWORD") ?: System.getenv("DOCKER_PASSWORD")
        }
    }

    from {
        image = "eclipse-temurin:21-jre-noble"
    }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.compression)
    implementation(libs.kotlinx.html.jvm)
    implementation("ch.qos.logback:logback-classic:1.5.32")
}

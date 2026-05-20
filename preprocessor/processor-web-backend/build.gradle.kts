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

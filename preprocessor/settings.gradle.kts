pluginManagement {
    repositories {
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ButterscotchPreprocessor"

include(":processor-common")
include(":processor-cli")
include(":processor-web")
include(":processor-web-backend")

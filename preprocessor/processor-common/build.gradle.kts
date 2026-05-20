plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()
    js(IR) {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }

        jsMain.dependencies {
            implementation(libs.kotlinWrappers.browser)
        }
    }
}

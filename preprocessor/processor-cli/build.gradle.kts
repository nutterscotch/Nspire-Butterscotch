plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.mrpowergamerbr.butterscotchpreprocessor.ButterscotchPreprocessor")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":processor-common"))
    implementation(libs.coroutines.core.jvm)
    implementation(libs.clikt)
}

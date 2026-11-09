plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(project(":engine-ytdlp"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.openyt.desktop.MainKt"
    }
}

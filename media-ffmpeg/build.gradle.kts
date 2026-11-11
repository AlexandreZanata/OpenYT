plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf("OPENYT_RUN_MEDIA_TESTS", "OPENYT_FFMPEG", "OPENYT_FFPROBE").forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
}

kotlin {
    androidTarget()
    jvm("desktop")
    jvmToolchain(17)
    sourceSets {
        val jvmSharedMain = maybeCreate("jvmSharedMain")
        jvmSharedMain.dependsOn(commonMain.get())
        getByName("androidMain").dependsOn(jvmSharedMain)
        getByName("desktopMain").dependsOn(jvmSharedMain)
        commonMain.dependencies { api(project(":core")) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android {
    namespace = "com.openyt.media.ffmpeg"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

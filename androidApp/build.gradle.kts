plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.openyt.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.openyt.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("openyt.versionCode").get().toInt()
        versionName = providers.gradleProperty("openyt.version").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += providers.gradleProperty("openyt.testAbi").getOrElse("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    val releaseKeystore = System.getenv("OPENYT_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("OPENYT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENYT_KEY_ALIAS")
                keyPassword = System.getenv("OPENYT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = providers.gradleProperty("openyt.testApplicationIdSuffix").orNull }
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(project(":engine-ytdlp"))
    implementation(project(":media-ffmpeg"))
    implementation(project(":data"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)

    androidTestImplementation(project(":data"))
    androidTestImplementation(compose.components.resources)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

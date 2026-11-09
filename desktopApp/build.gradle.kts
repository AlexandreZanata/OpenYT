import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
}

val appVersion = providers.gradleProperty("openyt.version").get()
val generatedVersionDir = layout.buildDirectory.dir("generated/version")

val generateVersionResource by tasks.registering {
    inputs.property("version", appVersion)
    outputs.dir(generatedVersionDir)
    doLast {
        val file = generatedVersionDir.get().file("openyt-version.properties").asFile
        file.parentFile?.mkdirs()
        file.writeText("version=$appVersion\n")
    }
}

sourceSets {
    main {
        resources.srcDir(generatedVersionDir)
    }
}

tasks.named("processResources") {
    dependsOn(generateVersionResource)
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(project(":engine-ytdlp"))
    implementation(project(":data"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.openyt.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            modules("java.sql")
            packageName = "OpenYT"
            packageVersion = providers.gradleProperty("openyt.version").get()
            description = "Local-first media downloader and library"
            vendor = "OpenYT"
            licenseFile.set(rootProject.file("LICENSE"))
            linux {
                debMaintainer = "163176324+AlexandreZanata@users.noreply.github.com"
                appCategory = "AudioVideo"
                menuGroup = "OpenYT"
            }
            macOS {
                bundleID = "com.openyt.desktop"
            }
        }
    }
}

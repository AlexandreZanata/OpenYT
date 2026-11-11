import java.net.URI
import java.util.zip.ZipInputStream
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

val platform = hostPlatform()
val architecture = hostArchitecture()
val bundledToolsDir = layout.buildDirectory.dir("bundled-tools/$platform-$architecture")

val prepareBundledTools by tasks.registering {
    description = "Downloads yt-dlp, FFmpeg, FFprobe, and Deno for the current platform."
    group = "distribution"
    inputs.property("platform", platform)
    inputs.property("architecture", architecture)
    outputs.dir(bundledToolsDir)
    doLast {
        val directory = bundledToolsDir.get().asFile.apply { mkdirs() }
        val executableSuffix = if (platform == "windows") ".exe" else ""
        val ytDlpName = "yt-dlp$executableSuffix"
        val ffmpegName = "ffmpeg$executableSuffix"
        val ffprobeName = "ffprobe$executableSuffix"
        val denoName = "deno$executableSuffix"

        val ytDlpAsset = when {
            platform == "windows" -> "yt-dlp.exe"
            platform == "macos" -> "yt-dlp_macos"
            architecture == "arm64" -> "yt-dlp_linux_aarch64"
            else -> "yt-dlp_linux"
        }
        download(
            "https://github.com/yt-dlp/yt-dlp/releases/download/$YT_DLP_VERSION/$ytDlpAsset",
            File(directory, ytDlpName),
        )

        val staticSuffix = when (platform to architecture) {
            "windows" to "x64" -> "win32-x64"
            "linux" to "x64" -> "linux-x64"
            "linux" to "arm64" -> "linux-arm64"
            "macos" to "x64" -> "darwin-x64"
            "macos" to "arm64" -> "darwin-arm64"
            else -> error("Unsupported desktop platform: $platform-$architecture")
        }
        val staticBase =
            "https://github.com/eugeneware/ffmpeg-static/releases/download/$FFMPEG_STATIC_VERSION"
        download("$staticBase/ffmpeg-$staticSuffix", File(directory, ffmpegName))
        download("$staticBase/ffprobe-$staticSuffix", File(directory, ffprobeName))

        val denoAsset = when (platform to architecture) {
            "linux" to "x64" -> "deno-x86_64-unknown-linux-gnu.zip"
            "linux" to "arm64" -> "deno-aarch64-unknown-linux-gnu.zip"
            "macos" to "x64" -> "deno-x86_64-apple-darwin.zip"
            "macos" to "arm64" -> "deno-aarch64-apple-darwin.zip"
            "windows" to "x64" -> "deno-x86_64-pc-windows-msvc.zip"
            else -> error("Unsupported Deno platform: $platform-$architecture")
        }
        val denoArchive = File(directory, "deno.zip")
        download(
            "https://github.com/denoland/deno/releases/download/v$DENO_VERSION/$denoAsset",
            denoArchive,
        )
        extractExecutable(denoArchive, denoName, File(directory, denoName))
        denoArchive.delete()

        listOf(ytDlpName, ffmpegName, ffprobeName, denoName).forEach {
            File(directory, it).setExecutable(true)
        }
        rootProject.file("LICENSE").copyTo(File(directory, "LICENSE"), overwrite = true)
        rootProject.file("THIRD_PARTY_NOTICES.md")
            .copyTo(File(directory, "THIRD_PARTY_NOTICES.md"), overwrite = true)
    }
}

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
    implementation(libs.vlcj)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "com.openyt.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            appResourcesRootDir.set(layout.buildDirectory.dir("bundled-tools"))
            modules("java.sql")
            packageName = "OpenYT"
            packageVersion = providers.gradleProperty("openyt.version").get()
            description = "Local-first media downloader and library"
            vendor = "OpenYT"
            licenseFile.set(rootProject.file("LICENSE"))
            linux {
                iconFile.set(project.file("packaging/icons/openyt.png"))
                debMaintainer = "163176324+AlexandreZanata@users.noreply.github.com"
                appCategory = "AudioVideo"
                menuGroup = "OpenYT"
            }
            windows {
                iconFile.set(project.file("packaging/icons/openyt.ico"))
            }
            macOS {
                iconFile.set(project.file("packaging/icons/openyt.icns"))
                bundleID = "com.openyt.desktop"
            }
        }
    }
}

tasks.matching {
    it.name in setOf(
        "createDistributable",
        "prepareAppResources",
        "packageDeb",
        "packageMsi",
        "packageDmg",
        "packageRpm",
    )
}.configureEach {
    dependsOn(prepareBundledTools)
}

val YT_DLP_VERSION = "2026.10.26"
val FFMPEG_STATIC_VERSION = "b6.1.1"
val DENO_VERSION = "2.9.8"

fun hostPlatform(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }
}

fun hostArchitecture(): String {
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    return when (arch) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
}

fun download(url: String, target: File) {
    target.parentFile?.mkdirs()
    val connection = URI(url).toURL().openConnection() as java.net.HttpURLConnection
    connection.setRequestProperty("User-Agent", "OpenYT-build")
    connection.connectTimeout = 30_000
    connection.readTimeout = 300_000
    try {
        check(connection.responseCode in 200..299) {
            "Failed to download $url (HTTP ${connection.responseCode})"
        }
        connection.inputStream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    } finally {
        connection.disconnect()
    }
}

fun extractExecutable(archive: File, entryName: String, target: File) {
    ZipInputStream(archive.inputStream().buffered()).use { stream ->
        var entry = stream.nextEntry
        while (entry != null) {
            if (entry.name.substringAfterLast('/') == entryName) {
                target.outputStream().use { stream.copyTo(it) }
                return
            }
            entry = stream.nextEntry
        }
    }
    error("$entryName not found in ${archive.name}")
}

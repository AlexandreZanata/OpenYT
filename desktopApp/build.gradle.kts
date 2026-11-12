import java.security.MessageDigest
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

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf("OPENYT_RUN_LOCAL_ENGINE_TESTS", "OPENYT_RUN_MEDIA_TESTS", "OPENYT_RUN_PLAYBACK_TESTS", "OPENYT_RUN_NETWORK_TESTS",
        "OPENYT_FFMPEG", "OPENYT_FFPROBE", "OPENYT_YTDLP", "OPENYT_JS_RUNTIME").forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
}

val prepareBundledTools by tasks.registering {
    description = "Downloads yt-dlp, FFmpeg, FFprobe, and Deno for the current platform."
    group = "distribution"
    inputs.property("platform", platform)
    inputs.property("architecture", architecture)
    inputs.property("linuxFfmpeg", "8.1.3-27-g8dca1586f4-20261031")
    inputs.property("macFfmpeg", "9.0.2-20260920")
    inputs.property("windowsVlc", "3.0.24")
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
        if (platform == "linux") {
            prepareLinuxFfmpeg(directory, architecture)
        } else if (platform == "macos") {
            prepareMacFfmpeg(directory, architecture)
        } else {
            download("$staticBase/ffmpeg-$staticSuffix", File(directory, ffmpegName))
            download("$staticBase/ffprobe-$staticSuffix", File(directory, ffprobeName))
        }

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
        if (platform == "windows") prepareWindowsVlc(directory)
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
    implementation(project(":media-ffmpeg"))
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
        jvmArgs += "-Dfile.encoding=UTF-8"

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
                shortcut = true
                menu = true
                menuGroup = "OpenYT"
            }
            macOS {
                iconFile.set(project.file("packaging/icons/openyt.icns"))
                bundleID = "com.openyt.desktop"
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array><dict><key>CFBundleURLName</key><string>OpenYT</string>
                        <key>CFBundleURLSchemes</key><array><string>openyt</string></array></dict></array>
                    """.trimIndent()
                }
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

fun prepareWindowsVlc(directory: File) {
    val archive = File(directory, "vlc.zip")
    try {
        download("https://ftp.osuosl.org/pub/videolan/vlc/3.0.24/win64/vlc-3.0.24-win64.zip", archive)
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } ==
            "fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323") { "VLC archive checksum mismatch" }
        val target = File(directory, "vlc").apply { deleteRecursively(); mkdirs() }.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val relative = entry.name.removePrefix("vlc-3.0.24/")
                val discPlugin = relative.substringAfterLast('/').let { it.contains("dvd", true) || it.contains("bluray", true) }
                val needed = relative in setOf("libvlc.dll", "libvlccore.dll", "COPYING.txt", "AUTHORS.txt", "THANKS.txt") || relative.startsWith("plugins/")
                if (!entry.isDirectory && needed && !discPlugin) {
                    val output = File(target, relative).canonicalFile
                    check(output.toPath().startsWith(target.toPath())) { "Invalid VLC archive path" }
                    output.parentFile.mkdirs()
                    output.outputStream().use { zip.copyTo(it) }
                }
                entry = zip.nextEntry
            }
        }
        check(File(target, "libvlc.dll").isFile && File(target, "plugins/codec").isDirectory) { "Incomplete VLC runtime" }
        rootProject.file("desktopApp/packaging/licenses/vlc-COPYING.LIB").copyTo(File(target, "COPYING.LIB"), overwrite = true)
        File(target, "SOURCE.txt").writeText("Unmodified VLC 3.0.24 runtime and plugins (disc access excluded).\nSource: https://download.videolan.org/pub/videolan/vlc/3.0.24/vlc-3.0.24.tar.xz\nhttps://code.videolan.org/videolan/vlc/-/tree/3.0.24\n")
    } finally { archive.delete() }
}

tasks.register<Jar>("windowsContractHarnessJar") {
    dependsOn(tasks.testClasses)
    archiveFileName.set("windows-contract-harness.jar")
    from(sourceSets.test.get().output)
}

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

fun download(url: String, target: File, userAgent: String = "OpenYT-build") {
    target.parentFile?.mkdirs()
    val connection = URI(url).toURL().openConnection() as java.net.HttpURLConnection
    connection.setRequestProperty("User-Agent", userAgent)
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

/** The previous Apple Silicon build lacks DASH demuxing needed by section downloads. */
fun prepareMacFfmpeg(directory: File, architecture: String) {
    val arm = architecture == "arm64"
    val cpu = if (arm) "arm64" else "amd64"
    val build = if (arm) "1789931890_9.0.2" else "1789931006_9.0.2"
    val checksums = if (arm) mapOf(
        "ffmpeg" to "c8ed4c4e6978a03c485edbfe4e0a5dc2380f8a30bba5150531b31b094492d924",
        "ffprobe" to "fcbe839537485eaee7a7a8bc5cbc0f90d53617e80943e8a5b2e31cb851197ea6",
    ) else mapOf(
        "ffmpeg" to "7c6b4125b191cbf773832dc51f424cf2b6bb7da43007d1e066f95909e47cacd4",
        "ffprobe" to "2322438ed2f6319a691291b247d09c69dcaa3a982460d1f269a7e1af335cfdfd",
    )
    for ((name, expected) in checksums) {
        val archive = File(directory, "$name-macos.zip")
        try {
            download("https://ffmpeg.martin-riedl.de/download/macos/$cpu/$build/$name.zip", archive, "Mozilla/5.0 OpenYT-build")
            val digest = MessageDigest.getInstance("SHA-256")
            archive.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "$name archive checksum mismatch" }
            extractExecutable(archive, name, File(directory, name))
        } finally { archive.delete() }
    }
    rootProject.file("LICENSE").copyTo(File(directory, "FFmpeg-LICENSE.txt"), overwrite = true)
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

/** The former Linux static 6.1.1 build crashes on a bounded live HLS fixture. */
fun prepareLinuxFfmpeg(directory: File, architecture: String) {
    val cpu = if (architecture == "arm64") "linuxarm64" else "linux64"
    val checksum = if (architecture == "arm64")
        "a08c0ee6e1c4e02139344b303002dcc7c37cf125d639f1fbd15dabee40fcb525"
    else "41e9c565594c390622cf968d82fa4135d9e9a11d06c1515644288cbc844e3380"
    val name = "ffmpeg-n8.1.3-27-g8dca1586f4-$cpu-gpl-8.1.tar.xz"
    val archive = File(directory, "ffmpeg-upstream.tar.xz")
    val extracted = File(directory, ".ffmpeg-unpack").apply { mkdirs() }
    try {
        download("https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/autobuild-2026-10-31-12-59/$name", archive)
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == checksum) { "FFmpeg archive checksum mismatch" }
        check(ProcessBuilder("tar", "-xJf", archive.absolutePath, "-C", extracted.absolutePath, "--strip-components=1").inheritIO().start().waitFor() == 0)
        listOf("ffmpeg", "ffprobe").forEach { File(extracted, "bin/$it").copyTo(File(directory, it), overwrite = true) }
        File(extracted, "LICENSE.txt").copyTo(File(directory, "FFmpeg-LICENSE.txt"), overwrite = true)
    } finally { archive.delete(); extracted.deleteRecursively() }
}

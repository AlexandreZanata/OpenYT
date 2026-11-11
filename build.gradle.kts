import groovy.json.JsonOutput
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.sqldelight) apply false
}

tasks.register("releaseSbom") {
    dependsOn(":desktopApp:prepareBundledTools")
    group = "distribution"
    description = "Generate a CycloneDX inventory of the resolved desktop and Android dependencies."
    doLast {
        val toolDirectory = project(":desktopApp").tasks.named("prepareBundledTools").get().outputs.files.singleFile
        val suffix = if (System.getProperty("os.name").contains("Windows", true)) ".exe" else ""
        fun nativeVersion(name: String): String {
            val process = ProcessBuilder(File(toolDirectory, "$name$suffix").absolutePath, "-version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) { "Cannot inspect bundled $name" }
            return Regex("$name version (?:n)?([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)").find(output)?.groupValues?.get(1)
                ?: error("Unknown bundled $name version")
        }
        val ffmpegVersion = nativeVersion("ffmpeg")
        val ffprobeVersion = nativeVersion("ffprobe")
        val targets = buildList {
            add(":desktopApp" to "runtimeClasspath")
            if (providers.gradleProperty("sbomAndroid").getOrElse("true").toBoolean()) add(":androidApp" to "releaseRuntimeClasspath")
        }
        val modules = targets
            .flatMap { (module, configuration) -> project(module).configurations.getByName(configuration)
                .incoming.resolutionResult.allComponents.mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier } }
            .distinctBy { it.toString() }
        val components = modules.sortedBy { it.toString() }.map { id ->
            mapOf("type" to "library", "group" to id.group, "name" to id.module, "version" to id.version,
                "purl" to "pkg:maven/${id.group}/${id.module}@${id.version}")
        } + buildList {
            add(mapOf("type" to "application", "name" to "yt-dlp", "version" to "2026.10.26"))
            add(mapOf("type" to "application", "name" to "FFmpeg", "version" to ffmpegVersion))
            add(mapOf("type" to "application", "name" to "FFprobe", "version" to ffprobeVersion))
            add(mapOf("type" to "application", "name" to "Deno", "version" to "2.9.8"))
            if (targets.any { it.first == ":androidApp" }) {
                add(mapOf("type" to "application", "name" to "yt-dlp-Android", "version" to "2025.11.12"))
                add(mapOf("type" to "application", "name" to "FFmpeg-Android", "version" to "7.1.1"))
                add(mapOf("type" to "application", "name" to "Python-Android", "version" to "3.12.11"))
                add(mapOf("type" to "application", "name" to "QuickJS-Android", "version" to "bundled-with-youtubedl-android-0.18.1"))
            }
        }

        val output = layout.buildDirectory.file("release/openyt-sbom.cdx.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
            "bomFormat" to "CycloneDX", "specVersion" to "1.5", "version" to 1,
            "metadata" to mapOf("component" to mapOf("type" to "application", "name" to "OpenYT", "version" to providers.gradleProperty("openyt.version").get())),
            "components" to components,
        ))))
    }
}

tasks.register("signReleaseManifest") {
    group = "distribution"
    description = "Sign SHA256SUMS.txt with the existing production release key."
    doLast {
        val manifest = file(providers.gradleProperty("releaseManifest").get())
        val path = requireNotNull(System.getenv("OPENYT_KEYSTORE_PATH")) { "Production signing key is required" }
        val storePassword = requireNotNull(System.getenv("OPENYT_KEYSTORE_PASSWORD")).toCharArray()
        val keyPassword = requireNotNull(System.getenv("OPENYT_KEY_PASSWORD")).toCharArray()
        try {
            val store = KeyStore.getInstance("JKS")
            FileInputStream(path).use { store.load(it, storePassword) }
            val key = store.getKey(requireNotNull(System.getenv("OPENYT_KEY_ALIAS")), keyPassword) as java.security.PrivateKey
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(key)
                update(manifest.readBytes())
                sign()
            }
            file("${manifest.absolutePath}.sig").writeBytes(signature)
        } finally {
            storePassword.fill('\u0000')
            keyPassword.fill('\u0000')
        }
    }
}

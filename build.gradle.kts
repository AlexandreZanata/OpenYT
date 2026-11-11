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
    group = "distribution"
    description = "Generate a CycloneDX inventory of the resolved desktop and Android dependencies."
    doLast {
        val modules = listOf(":desktopApp" to "runtimeClasspath", ":androidApp" to "releaseRuntimeClasspath")
            .flatMap { (module, configuration) -> project(module).configurations.getByName(configuration)
                .incoming.resolutionResult.allComponents.mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier } }
            .distinctBy { it.toString() }
        val components = modules.sortedBy { it.toString() }.map { id ->
            mapOf("type" to "library", "group" to id.group, "name" to id.module, "version" to id.version,
                "purl" to "pkg:maven/${id.group}/${id.module}@${id.version}")
        } + listOf(
            mapOf("type" to "application", "name" to "yt-dlp", "version" to "2026.10.26 (desktop) / 2025.11.12 (Android bundle)"),
            mapOf("type" to "application", "name" to "FFmpeg", "version" to "b6.1.1 (desktop) / 7.1.1 (Android)"),
            mapOf("type" to "application", "name" to "Deno", "version" to "2.9.8"),
        )
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

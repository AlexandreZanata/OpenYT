package com.openyt.data

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifiedReleaseTest {
    @Test fun productionSignatureVerifiesAndRejectsModifiedManifests() {
        val manifest = javaClass.getResourceAsStream("/release-trust/SHA256SUMS.txt")!!.use { it.readBytes() }
        val signature = javaClass.getResourceAsStream("/release-trust/SHA256SUMS.txt.sig")!!.use { it.readBytes() }
        assertTrue(VerifiedReleaseDownloader.verifyManifest(manifest, signature))
        assertFalse(VerifiedReleaseDownloader.verifyManifest(manifest + byteArrayOf(1), signature))
        signature[0] = (signature[0].toInt() xor 1).toByte()
        assertFalse(VerifiedReleaseDownloader.verifyManifest(manifest, signature))
    }

    @Test fun tamperedInstallerAndAmbiguousManifestsAreRejected() {
        val file = Files.createTempFile("openyt-integrity", ".deb").toFile()
        try {
            file.writeText("installer fixture")
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertTrue(VerifiedReleaseDownloader.matchesChecksum(file, digest))
            file.appendText("tampered")
            assertFalse(VerifiedReleaseDownloader.matchesChecksum(file, digest))
            assertNull(VerifiedReleaseDownloader.checksumFor("$digest  app.deb\n$digest  app.deb", "app.deb"))
            assertFalse(VerifiedReleaseDownloader.verifyManifest("changed".toByteArray(), ByteArray(256)))
            listOf("../app.deb", "a/b.apk", "C:\\app.msi", "..", "app\n.deb").forEach { assertFalse(VerifiedReleaseDownloader.safeName(it)) }
        } finally { file.delete() }
    }
}

package com.openyt.data

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EncryptedCookieRepositoryTest {
    @Test
    fun encryptsCookiesAtRestAndUsesTemporaryPlaintext() = runBlocking {
        val directory = File(System.getProperty("java.io.tmpdir"), "openyt-cookies-test")
        directory.deleteRecursively()
        val repository = EncryptedCookieRepository(directory)
        val content = "# Netscape HTTP Cookie File\n.openyt.invalid\tTRUE\t/\tFALSE\t0\tCANARY\tsecret-value\n"

        try {
            assertFalse(repository.isConfigured())
            repository.importCookies(content)
            assertTrue(repository.isConfigured())

            val encrypted = File(directory, "cookies.enc")
            assertTrue(encrypted.isFile)
            assertFalse(
                encrypted.readBytes().toString(Charsets.ISO_8859_1).contains("secret-value"),
                "cookies must not be stored in plaintext",
            )

            val tempPath = repository.createTemporaryFile()
            assertNotNull(tempPath)
            val temp = File(tempPath)
            assertTrue(temp.isFile)
            assertEquals(content, temp.readText())
            repository.deleteTemporaryFile(tempPath)
            assertFalse(temp.exists())

            repository.clear()
            assertFalse(repository.isConfigured())
            assertFalse(encrypted.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsEmptyCookieContent() = runBlocking {
        val directory = File(System.getProperty("java.io.tmpdir"), "openyt-cookies-empty")
        directory.deleteRecursively()
        val repository = EncryptedCookieRepository(directory)
        try {
            var failed = false
            try {
                repository.importCookies("   ")
            } catch (e: IllegalArgumentException) {
                failed = true
            }
            assertTrue(failed)
        } finally {
            directory.deleteRecursively()
        }
    }
}

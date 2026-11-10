package com.openyt.data

import com.openyt.core.application.CookieRepository
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores a Netscape cookies file encrypted with AES-GCM. The key and ciphertext
 * live in the app-private directory; plaintext exists only in a 0600 temp file
 * for the duration of a download.
 */
class EncryptedCookieRepository(
    private val directory: File,
) : CookieRepository {
    private val keyFile = File(directory, "cookies.key")
    private val dataFile = File(directory, "cookies.enc")

    override suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        dataFile.isFile && dataFile.length() > 0
    }

    override suspend fun importCookies(content: String) = withContext(Dispatchers.IO) {
        require(content.isNotBlank()) { "cookies content is empty" }
        directory.mkdirs()
        val key = loadOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.iv + cipher.doFinal(content.toByteArray(Charsets.UTF_8))
        dataFile.writeBytes(encrypted)
        restrict(dataFile)
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        dataFile.delete()
        keyFile.delete()
        directory.listFiles { file -> file.name.startsWith(TEMP_PREFIX) }?.forEach { it.delete() }
        Unit
    }

    override suspend fun createTemporaryFile(): String? = withContext(Dispatchers.IO) {
        if (!dataFile.isFile || dataFile.length() <= IV_LENGTH) return@withContext null
        val key = loadOrCreateKey()
        val payload = dataFile.readBytes()
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val plain = cipher.doFinal(payload.copyOfRange(IV_LENGTH, payload.size))
        val temp = File.createTempFile(TEMP_PREFIX, ".txt", directory)
        temp.writeBytes(plain)
        restrict(temp)
        temp.absolutePath
    }

    override suspend fun deleteTemporaryFile(path: String) = withContext(Dispatchers.IO) {
        File(path).delete()
        Unit
    }

    private fun loadOrCreateKey(): SecretKey {
        if (keyFile.isFile && keyFile.length() == KEY_BYTES) {
            return SecretKeySpec(keyFile.readBytes(), "AES")
        }
        val generator = KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }
        val key = generator.generateKey()
        directory.mkdirs()
        keyFile.writeBytes(key.encoded)
        restrict(keyFile)
        return key
    }

    private fun restrict(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
        const val KEY_BYTES = 32L
        const val TEMP_PREFIX = "openyt-cookies-"
    }
}

package com.openyt.data

import com.openyt.core.domain.MediaKind
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileFolderScannerTest {
    @Test
    fun scansSupportedMediaWithoutChangingFiles() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "openyt-folder-scan")
        root.deleteRecursively()
        root.mkdirs()
        val movie = File(root, "movie.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val song = File(root, "song.mp3").apply { writeBytes(byteArrayOf(4, 5)) }
        File(root, "readme.txt").writeText("ignore")
        val nested = File(root, "sub").apply { mkdirs() }
        val clip = File(nested, "clip.webm").apply { writeBytes(byteArrayOf(6)) }

        try {
            val before = listOf(movie, song, clip).associate { it.absolutePath to hash(it) }
            val scanned = FileFolderScanner().scan(root.absolutePath)

            assertEquals(3, scanned.size)
            assertEquals(MediaKind.VIDEO, scanned.first { it.title == "movie" }.kind)
            assertEquals(MediaKind.AUDIO, scanned.first { it.title == "song" }.kind)
            assertEquals(MediaKind.VIDEO, scanned.first { it.title == "clip" }.kind)
            assertEquals(before, listOf(movie, song, clip).associate { it.absolutePath to hash(it) })
            assertTrue(scanned.none { it.title == "readme" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun returnsEmptyForMissingDirectory() = runBlocking {
        assertTrue(FileFolderScanner().scan("/definitely/missing/openyt").isEmpty())
    }

    private fun hash(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it)
        }
}

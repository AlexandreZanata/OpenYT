package com.openyt.android

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidFileProviderTest {
    @Test
    fun exposesDownloadedFilesThroughContentUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val file = File(directory, "share-test.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val read = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), read)
        file.delete()
    }
}

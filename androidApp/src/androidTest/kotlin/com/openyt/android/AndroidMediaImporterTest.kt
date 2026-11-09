package com.openyt.android

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.android.importer.MediaFileImporter
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaImporterTest {
    @Test
    fun importsASelectedContentUriIntoAppStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val downloads = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val source = File(downloads, "import-source.mp4").apply {
            writeBytes(byteArrayOf(9, 8, 7, 6))
        }
        val importer = MediaFileImporter(context, downloads.absolutePath)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            source,
        )

        val entry = importer.importUri(uri)

        assertNotNull(entry)
        val imported = File(entry!!.filePath)
        assertTrue(imported.isFile)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), imported.readBytes())
        imported.delete()
        source.delete()
    }
}

package com.openyt.data

import com.openyt.core.application.BulkChange
import com.openyt.core.application.BulkPlan
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileBulkFileOperatorTest {
    @Test
    fun swapsFilesWithoutOverwriting() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "openyt-bulk-swap")
        root.deleteRecursively()
        root.mkdirs()
        val a = File(root, "a.txt").apply { writeText("A") }
        val b = File(root, "b.txt").apply { writeText("B") }
        try {
            val plan = BulkPlan(
                changes = listOf(
                    BulkChange("a", a.absolutePath, b.absolutePath),
                    BulkChange("b", b.absolutePath, a.absolutePath),
                ),
            )

            val result = FileBulkFileOperator().apply(plan)

            assertTrue(result.success, result.toString())
            assertEquals("B", a.readText())
            assertEquals("A", b.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rollsBackWhenATargetAppears() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "openyt-bulk-rollback")
        root.deleteRecursively()
        root.mkdirs()
        val a = File(root, "a.txt").apply { writeText("A") }
        val b = File(root, "b.txt").apply { writeText("B") }
        val x = File(root, "x.txt")
        val y = File(root, "y.txt")
        try {
            val plan = BulkPlan(
                changes = listOf(
                    BulkChange("a", a.absolutePath, x.absolutePath),
                    BulkChange("b", b.absolutePath, y.absolutePath),
                ),
            )
            y.writeText("occupied")

            val result = FileBulkFileOperator().apply(plan)

            assertTrue(result.rolledBack)
            assertFalse(result.success)
            assertTrue(a.isFile)
            assertTrue(b.isFile)
            assertEquals("A", a.readText())
            assertEquals("B", b.readText())
            assertFalse(x.exists())
            assertEquals("occupied", y.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}

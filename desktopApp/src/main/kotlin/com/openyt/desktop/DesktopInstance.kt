package com.openyt.desktop

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** One coordinator per database. GUI handoff uses private local files, never a server. */
class DesktopInstance private constructor(
    private val root: File,
    private val channel: FileChannel,
    private val lock: FileLock,
    private val key: String,
) : AutoCloseable {
    fun takeLinks(): List<String?> {
        val inbox = File(root, "instance-inbox")
        return inbox.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9-]{36}\\.link")) }
            .sortedBy { it.lastModified() }.take(50).mapNotNull { file ->
                try {
                    if (file.length() > 65_536 || System.currentTimeMillis() - file.lastModified() > 60_000) return@mapNotNull null
                    val text = file.readText(Charsets.UTF_8)
                    if (text == FOCUS) Link(null) else com.openyt.core.application.OpenYtLink.parse(text)?.let(::Link)
                } finally { file.delete() }
            }.map { it.url }
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { if (lock.isValid) lock.release() } finally { channel.close(); owned.remove(key) }
        }
    }

    private data class Link(val url: String?)

    companion object {
        private val owned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private const val FOCUS = "OPENYT_FOCUS"

        @Synchronized
        fun acquire(root: File, gui: Boolean): DesktopInstance? {
            val key = root.canonicalPath
            if (key in owned) return null
            root.mkdirs()
            privateDirectory(root)
            val channel = FileChannel.open(File(root, "instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            val lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock == null) { channel.close(); return null }
            try {
                File(root, "instance.owner").writeText(if (gui) "gui" else "cli", Charsets.UTF_8)
                owned.add(key)
                return DesktopInstance(root, channel, lock, key)
            } catch (e: Exception) { lock.release(); channel.close(); throw e }
        }

        fun forward(root: File, url: String?): Boolean = runCatching {
            if (File(root, "instance.owner").readText(Charsets.UTF_8) != "gui") return@runCatching false
            val text = if (url == null) FOCUS else com.openyt.core.application.OpenYtLink.create(url) ?: return@runCatching false
            if (text.toByteArray(Charsets.UTF_8).size > 65_536) return@runCatching false
            val inbox = File(root, "instance-inbox").apply { mkdirs() }
            privateDirectory(inbox)
            val id = UUID.randomUUID().toString()
            val temporary = File(inbox, "$id.part")
            try {
                temporary.writeText(text, Charsets.UTF_8)
                Files.move(temporary.toPath(), File(inbox, "$id.link").toPath(), StandardCopyOption.ATOMIC_MOVE)
                true
            } finally { temporary.delete() }
        }.getOrDefault(false)

        private fun privateDirectory(directory: File) {
            runCatching { Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------")) }
        }
    }
}

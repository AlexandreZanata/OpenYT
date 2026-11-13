package com.openyt.desktop.player

import com.sun.jna.NativeLibrary
import java.io.File
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy
import uk.co.caprica.vlcj.binding.lib.LibC

/** Prefer the verified, application-owned Windows runtime over machine installations. */
internal object DesktopVlcRuntime {
    // Keep the core library loaded while libvlc and its plugins use it.
    private var core: NativeLibrary? = null

    fun bundledDirectory(
        resources: String? = System.getProperty("compose.application.resources.dir"),
        platform: String = System.getProperty("os.name").orEmpty(),
    ): File? {
        if (!platform.contains("windows", true) || resources == null) return null
        return File(resources, "vlc").takeIf { directory ->
            File(directory, "libvlc.dll").isFile && File(directory, "libvlccore.dll").isFile &&
                File(directory, "plugins").isDirectory
        }
    }

    @Synchronized
    fun discover(): Boolean {
        val bundled = bundledDirectory()
        if (bundled == null) {
            val platform = System.getProperty("os.name").orEmpty()
            if (platform.contains("linux", ignoreCase = true)) {
                return discoverLinux()
            }
            return NativeDiscovery().discover()
        }
        // This VLC build imports getenv from msvcrt. Ignore a machine VLC's
        // inherited plugin path; libvlccore resolves its own adjacent plugins.
        check(LibC.INSTANCE._putenv("VLC_PLUGIN_PATH=") == 0) { "Cannot isolate bundled VLC plugins" }
        val strategy = object : NativeDiscoveryStrategy {
            override fun supported() = true
            override fun discover() = bundled.absolutePath
            override fun onFound(path: String): Boolean {
                core = NativeLibrary.getInstance(File(bundled, "libvlccore.dll").absolutePath)
                return true
            }
            override fun onSetPluginPath(path: String) = false
        }
        return NativeDiscovery(strategy).discover()
    }

    private fun discoverLinux(): Boolean {
        val ldPaths = System.getenv("LD_LIBRARY_PATH")
            ?.split(File.pathSeparatorChar)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val candidates = ldPaths + listOf(
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu",
            "/usr/lib64",
            "/usr/lib",
            "/usr/local/lib",
            "/app/lib",
        )
        val foundDir = candidates
            .asSequence()
            .map(::File)
            .firstOrNull { dir ->
                dir.isDirectory && (File(dir, "libvlc.so.5").isFile || File(dir, "libvlc.so").isFile)
            }
        if (foundDir != null) {
            val strategy = object : NativeDiscoveryStrategy {
                override fun supported() = true
                override fun discover() = foundDir.absolutePath
                override fun onFound(path: String) = true
                override fun onSetPluginPath(path: String) = false
            }
            if (NativeDiscovery(strategy).discover()) return true
        }
        return runCatching {
            NativeLibrary.getInstance("vlc")
            true
        }.getOrDefault(false)
    }
}

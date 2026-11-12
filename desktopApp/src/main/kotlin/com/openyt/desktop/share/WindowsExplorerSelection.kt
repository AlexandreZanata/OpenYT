package com.openyt.desktop.share

import com.sun.jna.platform.win32.COM.util.IDispatch
import com.sun.jna.platform.win32.COM.util.ObjectFactory
import com.sun.jna.platform.win32.COM.util.annotation.ComInterface
import com.sun.jna.platform.win32.COM.util.annotation.ComObject
import java.io.File

/** Called only inside WindowsShell's COM apartment, never on the UI thread. */
internal object WindowsExplorerSelection {
    @ComObject(progId = "Shell.Application")
    @ComInterface(iid = "{00020400-0000-0000-C000-000000000046}")
    interface ShellApplication : IDispatch

    fun confirm(file: File): Boolean {
        val parent = file.absoluteFile.parentFile ?: return false
        val deadline = System.nanoTime() + 5_000_000_000L
        var stable = 0
        do {
            if (selectInLoadedView(parent.path, file)) stable++ else stable = 0
            if (stable >= 3) return true
            Thread.sleep(200)
        } while (System.nanoTime() < deadline)
        return false
    }

    private fun selectInLoadedView(parent: String, file: File): Boolean {
        val factory = ObjectFactory()
        try {
            val shell = factory.createObject(ShellApplication::class.java)
            val windows = shell.invokeMethod(IDispatch::class.java, "Windows")
            val views = mutableListOf<IDispatch>()
            for (index in 0 until windows.count()) {
                runCatching {
                    val browser = windows.invokeMethod(IDispatch::class.java, "Item", index)
                    if (browser.getProperty(Boolean::class.javaObjectType, "Busy") ||
                        browser.getProperty(Int::class.javaObjectType, "ReadyState") != 4) return@runCatching
                    val view = browser.getProperty(IDispatch::class.java, "Document")
                    val folder = view.getProperty(IDispatch::class.java, "Folder")
                    val path = folder.getProperty(IDispatch::class.java, "Self").getProperty(String::class.java, "Path")
                    if (path.equals(parent, ignoreCase = true)) views += view
                }
            }
            for (view in views) {
                val selected = view.invokeMethod(IDispatch::class.java, "SelectedItems")
                for (index in 0 until selected.count()) {
                    val path = selected.invokeMethod(IDispatch::class.java, "Item", index).getProperty(String::class.java, "Path")
                    if (path.equals(file.absolutePath, ignoreCase = true)) return true
                }
            }
            views.firstOrNull()?.let { view ->
                val folder = view.getProperty(IDispatch::class.java, "Folder")
                val item = folder.invokeMethod(IDispatch::class.java, "ParseName", file.name)
                // Select, deselect others, ensure visible and focus, without edit mode.
                view.invokeMethod(Void.TYPE, "SelectItem", item, 1 or 4 or 8 or 16)
            }
        } catch (_: Exception) {
            // The initial view may be replaced while Explorer discovers video folders.
        } finally { factory.disposeAll() }
        return false
    }

    private fun IDispatch.count(): Int = getProperty(Int::class.javaObjectType, "Count")
}

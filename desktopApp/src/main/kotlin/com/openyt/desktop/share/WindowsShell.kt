package com.openyt.desktop.share

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Native Unicode shell operations; file names are data, never shell commands. */
internal object WindowsShell {
    private interface Api : StdCallLibrary {
        fun ShellExecuteW(window: Pointer?, operation: WString, file: WString, parameters: WString?, directory: WString?, show: Int): Pointer?
        fun SHParseDisplayName(name: WString, context: Pointer?, item: PointerByReference, attributes: Int, result: Pointer?): HRESULT
        fun SHOpenFolderAndSelectItems(item: Pointer, count: Int, children: Pointer?, flags: Int): HRESULT
    }

    private val api by lazy { Native.load("shell32", Api::class.java) }
    private val executor by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "openyt-windows-shell").apply { isDaemon = true } }
    }

    fun open(file: File): Boolean = inApartment {
        Pointer.nativeValue(api.ShellExecuteW(null, WString("open"), WString(file.absolutePath), null, null, 1)) > 32
    }

    fun reveal(file: File): Boolean = inApartment {
        val item = PointerByReference()
        if (api.SHParseDisplayName(WString(file.absolutePath), null, item, 0, null).toInt() < 0) return@inApartment false
        val pointer = item.value ?: return@inApartment false
        try {
            // cidl=0 means select this absolute item in its parent folder.
            api.SHOpenFolderAndSelectItems(pointer, 0, null, 0).toInt() >= 0 &&
                WindowsExplorerSelection.confirm(file)
        } finally { Ole32.INSTANCE.CoTaskMemFree(pointer) }
    }

    private fun inApartment(action: () -> Boolean): Boolean = runCatching {
        executor.submit<Boolean> {
            val initialized = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).toInt()
            if (initialized < 0) false else try { action() } finally { Ole32.INSTANCE.CoUninitialize() }
        }.get(10, TimeUnit.SECONDS)
    }.getOrDefault(false)
}

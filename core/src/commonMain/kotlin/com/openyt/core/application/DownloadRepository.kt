package com.openyt.core.application

import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry

/**
 * Persistence port for the download queue and the local library.
 *
 * Removing an entry from the library must never delete the user's file. File
 * deletion is a separate, explicit operation that is not part of this port.
 */
interface DownloadRepository {
    suspend fun loadTasks(): List<DownloadTask>

    suspend fun insertTask(task: DownloadTask)

    suspend fun updateTask(task: DownloadTask)

    suspend fun deleteTask(id: String)

    /** Marks tasks left active by a previous process as interrupted. */
    suspend fun markActiveInterrupted()

    suspend fun loadLibrary(): List<LibraryEntry>

    suspend fun insertLibraryEntry(entry: LibraryEntry)

    suspend fun deleteLibraryEntry(id: String)
}

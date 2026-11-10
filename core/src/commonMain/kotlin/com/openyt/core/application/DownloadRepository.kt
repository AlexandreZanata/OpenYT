package com.openyt.core.application

import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.SmartCollection

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

    suspend fun addLibraryTag(entryId: String, tag: String)

    suspend fun removeLibraryTag(entryId: String, tag: String)

    suspend fun loadCollections(): List<Collection>

    suspend fun insertCollection(collection: Collection)

    suspend fun deleteCollection(id: String)

    suspend fun loadCollectionMembership(): Map<String, Set<String>>

    suspend fun addEntryToCollection(collectionId: String, entryId: String)

    suspend fun removeEntryFromCollection(collectionId: String, entryId: String)

    suspend fun loadSmartCollections(): List<SmartCollection>

    suspend fun insertSmartCollection(collection: SmartCollection)

    suspend fun deleteSmartCollection(id: String)
}

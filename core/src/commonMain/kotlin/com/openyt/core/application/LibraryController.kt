package com.openyt.core.application

import com.openyt.core.domain.Collection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns collections and their membership, persisted through [DownloadRepository].
 * Deleting a collection or removing an entry only changes metadata; files and
 * library records are never touched.
 */
class LibraryController(
    private val repository: DownloadRepository?,
    scope: CoroutineScope,
) {
    private val _collections = MutableStateFlow<List<Collection>>(emptyList())
    val collections: StateFlow<List<Collection>> = _collections.asStateFlow()

    private val _membership = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val membership: StateFlow<Map<String, Set<String>>> = _membership.asStateFlow()

    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var nextId = 0

    init {
        scope.launch {
            for (operation in writes) {
                runCatching { operation() }
            }
        }
    }

    suspend fun restore() {
        val repo = repository ?: return
        runCatching {
            val loaded = repo.loadCollections()
            _collections.value = loaded
            _membership.value = repo.loadCollectionMembership()
            nextId = loaded
                .mapNotNull { it.id.removePrefix(ID_PREFIX).toIntOrNull() }
                .maxOrNull() ?: 0
        }
    }

    fun createCollection(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null

        _collections.value.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            ?.let { return it.id }

        val collection = Collection("$ID_PREFIX${++nextId}", trimmed)
        _collections.update { it + collection }
        persist { repository?.insertCollection(collection) }
        return collection.id
    }

    fun deleteCollection(id: String) {
        _collections.update { list -> list.filterNot { it.id == id } }
        _membership.update { it - id }
        persist { repository?.deleteCollection(id) }
    }

    fun toggleEntry(collectionId: String, entryId: String) {
        val current = _membership.value[collectionId].orEmpty()
        val added = entryId !in current
        val next = if (added) current + entryId else current - entryId
        _membership.update { it + (collectionId to next) }
        persist {
            if (added) {
                repository?.addEntryToCollection(collectionId, entryId)
            } else {
                repository?.removeEntryFromCollection(collectionId, entryId)
            }
        }
    }

    fun entriesIn(collectionId: String): Set<String> = _membership.value[collectionId].orEmpty()

    private fun persist(operation: suspend () -> Unit) {
        writes.trySend(operation)
    }

    private companion object {
        const val ID_PREFIX = "collection-"
    }
}

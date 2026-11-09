package com.openyt.core.application

import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryControllerTest {
    @Test
    fun createsCollectionsAndRejectsDuplicateNames() = runTest {
        val repository = FakeRepository()
        val controller = LibraryController(repository, coordinatorScope())

        val first = controller.createCollection("Favorites")
        val second = controller.createCollection("  favorites ")
        val blank = controller.createCollection("   ")
        advanceUntilIdle()

        assertEquals(first, second)
        assertNull(blank)
        assertEquals(1, controller.collections.value.size)
        assertEquals(1, repository.collections.size)
    }

    @Test
    fun togglesMembershipAndPersists() = runTest {
        val repository = FakeRepository()
        val controller = LibraryController(repository, coordinatorScope())
        val id = controller.createCollection("Favorites")!!

        controller.toggleEntry(id, "entry-1")
        controller.toggleEntry(id, "entry-2")
        advanceUntilIdle()

        assertEquals(setOf("entry-1", "entry-2"), controller.entriesIn(id))
        assertEquals(setOf("entry-1", "entry-2"), repository.membership.getValue(id))

        controller.toggleEntry(id, "entry-1")
        advanceUntilIdle()

        assertEquals(setOf("entry-2"), controller.entriesIn(id))
        assertEquals(setOf("entry-2"), repository.membership.getValue(id))
    }

    @Test
    fun deletesCollectionAndMembership() = runTest {
        val repository = FakeRepository()
        val controller = LibraryController(repository, coordinatorScope())
        val id = controller.createCollection("Favorites")!!
        controller.toggleEntry(id, "entry-1")

        controller.deleteCollection(id)
        advanceUntilIdle()

        assertTrue(controller.collections.value.isEmpty())
        assertTrue(controller.membership.value.isEmpty())
        assertTrue(repository.collections.isEmpty())
        assertTrue(repository.membership.isEmpty())
    }

    @Test
    fun restoresCollectionsAndContinuesIds() = runTest {
        val repository = FakeRepository(
            collections = mutableListOf(Collection("collection-4", "Old")),
            membership = mutableMapOf("collection-4" to mutableSetOf("entry-1")),
        )
        val controller = LibraryController(repository, coordinatorScope())

        controller.restore()
        val newId = controller.createCollection("New")
        advanceUntilIdle()

        assertEquals("collection-5", newId)
        assertEquals(2, controller.collections.value.size)
        assertEquals(setOf("entry-1"), controller.entriesIn("collection-4"))
    }

    private fun TestScope.coordinatorScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

    private class FakeRepository(
        val collections: MutableList<Collection> = mutableListOf(),
        val membership: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    ) : DownloadRepository {
        override suspend fun loadTasks(): List<DownloadTask> = emptyList()
        override suspend fun insertTask(task: DownloadTask) = Unit
        override suspend fun updateTask(task: DownloadTask) = Unit
        override suspend fun deleteTask(id: String) = Unit
        override suspend fun markActiveInterrupted() = Unit
        override suspend fun loadLibrary(): List<LibraryEntry> = emptyList()
        override suspend fun insertLibraryEntry(entry: LibraryEntry) = Unit
        override suspend fun deleteLibraryEntry(id: String) = Unit
        override suspend fun loadCollections(): List<Collection> = collections.toList()

        override suspend fun insertCollection(collection: Collection) {
            collections.removeAll { it.id == collection.id }
            collections += collection
        }

        override suspend fun deleteCollection(id: String) {
            collections.removeAll { it.id == id }
            membership.remove(id)
        }

        override suspend fun loadCollectionMembership(): Map<String, Set<String>> =
            membership.mapValues { it.value.toSet() }

        override suspend fun addEntryToCollection(collectionId: String, entryId: String) {
            membership.getOrPut(collectionId) { mutableSetOf() } += entryId
        }

        override suspend fun removeEntryFromCollection(collectionId: String, entryId: String) {
            membership[collectionId]?.remove(entryId)
        }
    }
}

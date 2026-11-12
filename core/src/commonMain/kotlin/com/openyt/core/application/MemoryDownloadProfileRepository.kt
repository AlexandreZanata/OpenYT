package com.openyt.core.application

import com.openyt.core.domain.DownloadProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Thread-safe in-memory profile repository seeded with built-in profiles.
 * Used for testing, offline previews, and fallback when persistent storage is unavailable.
 */
class MemoryDownloadProfileRepository(
    initialProfiles: List<DownloadProfile> = DownloadProfile.BuiltIns,
    initialDefaultProfileId: String? = null,
) : DownloadProfileRepository {
    private val _profiles = MutableStateFlow(initialProfiles)
    val profiles = _profiles.asStateFlow()

    private var defaultId: String? = initialDefaultProfileId
    private val bindings = mutableMapOf<String, ProfileDestinationBinding>()

    override suspend fun loadProfiles(): List<DownloadProfile> = _profiles.value.toList()

    override suspend fun getProfile(id: String): DownloadProfile? =
        _profiles.value.firstOrNull { it.id == id }

    override suspend fun saveCustomProfile(profile: DownloadProfile) {
        require(profile.name.isNotBlank()) { "Profile name cannot be blank" }
        require(!profile.isBuiltIn) { "Cannot modify built-in profile: ${profile.id}" }
        val current = _profiles.value.toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            require(!current[index].isBuiltIn) { "Cannot overwrite built-in profile: ${profile.id}" }
            current[index] = profile
        } else {
            current.add(profile)
        }
        _profiles.value = current
    }

    override suspend fun cloneProfile(source: DownloadProfile, newId: String, newName: String): DownloadProfile {
        val cloned = source.copy(id = newId, name = newName, isBuiltIn = false)
        saveCustomProfile(cloned)
        return cloned
    }

    override suspend fun deleteCustomProfile(id: String): Boolean {
        val profile = getProfile(id) ?: return false
        require(!profile.isBuiltIn) { "Cannot delete built-in profile: $id" }
        _profiles.update { list -> list.filter { it.id != id } }
        if (defaultId == id) defaultId = null
        deleteDestinationBinding(id)
        return true
    }

    override suspend fun getDefaultProfileId(): String? = defaultId

    override suspend fun setDefaultProfileId(id: String?) {
        defaultId = id
    }

    override suspend fun getDestinationBinding(profileId: String): ProfileDestinationBinding? =
        bindings[profileId]

    override suspend fun saveDestinationBinding(binding: ProfileDestinationBinding) {
        bindings[binding.profileId] = binding
    }

    override suspend fun deleteDestinationBinding(profileId: String) {
        bindings.remove(profileId)
    }
}

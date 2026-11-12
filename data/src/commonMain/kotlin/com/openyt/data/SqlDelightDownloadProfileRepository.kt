package com.openyt.data

import com.openyt.core.application.DownloadProfileRepository
import com.openyt.core.application.ProfileDefinitionCodec
import com.openyt.core.application.ProfileDestinationBinding
import com.openyt.core.domain.DownloadProfile
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlDelightDownloadProfileRepository(
    database: OpenYtDatabase,
) : DownloadProfileRepository {
    private val queries = database.downloadQueries

    override suspend fun loadProfiles(): List<DownloadProfile> = withContext(Dispatchers.Default) {
        ensureBuiltInsSeeded()
        val rows = queries.selectAllProfiles().executeAsList()
        val profiles = rows.mapNotNull { row ->
            val decoded = ProfileDefinitionCodec.decode(row.definition_json).getOrNull()
            if (decoded != null) {
                decoded
            } else if (row.is_builtin == 1L) {
                DownloadProfile.BuiltIns.find { it.id == row.id }
            } else {
                null
            }
        }
        val builtInMap = profiles.filter { it.isBuiltIn }.associateBy { it.id }
        val canonicalBuiltIns = DownloadProfile.BuiltIns.map { builtIn ->
            builtInMap[builtIn.id] ?: builtIn
        }
        val customProfiles = profiles.filter { !it.isBuiltIn }
        canonicalBuiltIns + customProfiles
    }

    override suspend fun getProfile(id: String): DownloadProfile? = withContext(Dispatchers.Default) {
        val row = queries.selectProfileById(id).executeAsOneOrNull()
        if (row != null) {
            val decoded = ProfileDefinitionCodec.decode(row.definition_json).getOrNull()
            if (decoded != null) {
                return@withContext decoded
            }
            if (row.is_builtin == 1L) {
                return@withContext DownloadProfile.BuiltIns.find { it.id == row.id }
            }
            return@withContext null
        }
        DownloadProfile.BuiltIns.find { it.id == id }
    }

    override suspend fun saveCustomProfile(profile: DownloadProfile) = withContext(Dispatchers.Default) {
        require(profile.id.isNotBlank()) { "Profile ID cannot be blank" }
        require(profile.name.isNotBlank()) { "Profile name cannot be blank" }
        require(!profile.isBuiltIn) { "Cannot save a custom profile with isBuiltIn = true" }
        require(DownloadProfile.BuiltIns.none { it.id == profile.id }) {
            "Cannot overwrite built-in profile with ID: ${profile.id}"
        }

        val allProfiles = loadProfiles()
        val nameConflict = allProfiles.any {
            it.id != profile.id && it.name.trim().equals(profile.name.trim(), ignoreCase = true)
        }
        require(!nameConflict) {
            "A profile with name '${profile.name.trim()}' already exists"
        }

        val cleanProfile = profile.copy(name = profile.name.trim(), isBuiltIn = false)
        val encoded = ProfileDefinitionCodec.encode(cleanProfile)
        queries.insertProfile(
            id = cleanProfile.id,
            name = cleanProfile.name,
            is_builtin = 0L,
            definition_json = encoded,
        )
        Unit
    }

    override suspend fun cloneProfile(source: DownloadProfile, newId: String, newName: String): DownloadProfile {
        require(newId.isNotBlank()) { "Profile ID cannot be blank" }
        require(newName.isNotBlank()) { "Profile name cannot be blank" }
        val custom = source.copy(
            id = newId,
            name = newName.trim(),
            isBuiltIn = false,
        )
        saveCustomProfile(custom)
        return custom
    }

    override suspend fun deleteCustomProfile(id: String): Boolean = withContext(Dispatchers.Default) {
        if (DownloadProfile.BuiltIns.any { it.id == id }) {
            return@withContext false
        }
        val existing = queries.selectProfileById(id).executeAsOneOrNull()
        if (existing == null || existing.is_builtin == 1L) {
            return@withContext false
        }
        queries.deleteProfile(id)
        queries.deleteProfileBinding(id)
        val defaultId = queries.selectDefaultProfileId().executeAsOneOrNull()?.default_profile_id
        if (defaultId == id) {
            queries.updateDefaultProfileId(null)
        }
        true
    }

    override suspend fun getDefaultProfileId(): String? = withContext(Dispatchers.Default) {
        queries.selectDefaultProfileId().executeAsOneOrNull()?.default_profile_id
    }

    override suspend fun setDefaultProfileId(id: String?) = withContext(Dispatchers.Default) {
        if (id != null) {
            val exists = getProfile(id) != null
            require(exists) { "Profile with ID '$id' does not exist" }
        }
        queries.insertDefaultSettingsWithProfileId(id)
        queries.updateDefaultProfileId(id)
        Unit
    }

    override suspend fun getDestinationBinding(profileId: String): ProfileDestinationBinding? =
        withContext(Dispatchers.Default) {
            queries.selectProfileBinding(profileId).executeAsOneOrNull()?.let {
                ProfileDestinationBinding(
                    profileId = it.profile_id,
                    destinationDirectory = it.destination_directory,
                    storageTreeUri = it.storage_tree_uri,
                )
            }
        }

    override suspend fun saveDestinationBinding(binding: ProfileDestinationBinding) =
        withContext(Dispatchers.Default) {
            queries.insertProfileBinding(
                profile_id = binding.profileId,
                destination_directory = binding.destinationDirectory,
                storage_tree_uri = binding.storageTreeUri,
            )
            Unit
        }

    override suspend fun deleteDestinationBinding(profileId: String) =
        withContext(Dispatchers.Default) {
            queries.deleteProfileBinding(profileId)
            Unit
        }

    private fun ensureBuiltInsSeeded() {
        val existingIds = queries.selectAllProfiles().executeAsList().map { it.id }.toSet()
        for (builtin in DownloadProfile.BuiltIns) {
            if (builtin.id !in existingIds) {
                queries.insertProfile(
                    id = builtin.id,
                    name = builtin.name,
                    is_builtin = 1L,
                    definition_json = ProfileDefinitionCodec.encode(builtin),
                )
            }
        }
    }
}

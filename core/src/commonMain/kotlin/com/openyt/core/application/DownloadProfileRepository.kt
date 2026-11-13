package com.openyt.core.application

import com.openyt.core.domain.DownloadProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProfileDestinationBinding(
    val profileId: String,
    val destinationDirectory: String? = null,
    val storageTreeUri: String? = null,
)

enum class DestinationBindingStatus {
    VALID,
    MISSING_DIRECTORY,
    PERMISSION_DENIED,
    NO_BINDING,
}

fun interface DestinationPermissionChecker {
    fun hasAccess(directory: String?, treeUri: String?): Boolean
}

@Serializable
data class PersistedProfileRecord(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val profile: DownloadProfile,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

object ProfileDefinitionCodec {
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(profile: DownloadProfile): String =
        json.encodeToString(PersistedProfileRecord(PersistedProfileRecord.CURRENT_SCHEMA, profile))

    fun export(profile: DownloadProfile): String =
        prettyJson.encodeToString(PersistedProfileRecord(PersistedProfileRecord.CURRENT_SCHEMA, profile))

    fun decode(rawJson: String): Result<DownloadProfile> = runCatching {
        try {
            val record = json.decodeFromString<PersistedProfileRecord>(rawJson)
            require(record.schemaVersion <= PersistedProfileRecord.CURRENT_SCHEMA) {
                "Unsupported profile definition schema: ${record.schemaVersion}"
            }
            record.profile
        } catch (_: Exception) {
            json.decodeFromString<DownloadProfile>(rawJson)
        }
    }
}

interface DownloadProfileRepository {
    suspend fun loadProfiles(): List<DownloadProfile>

    suspend fun getProfile(id: String): DownloadProfile?

    suspend fun saveCustomProfile(profile: DownloadProfile)

    suspend fun cloneProfile(source: DownloadProfile, newId: String, newName: String): DownloadProfile

    suspend fun deleteCustomProfile(id: String): Boolean

    suspend fun getDefaultProfileId(): String?

    suspend fun setDefaultProfileId(id: String?)

    suspend fun getDestinationBinding(profileId: String): ProfileDestinationBinding?

    suspend fun saveDestinationBinding(binding: ProfileDestinationBinding)

    suspend fun deleteDestinationBinding(profileId: String)

    fun validateDestinationAccess(
        binding: ProfileDestinationBinding?,
        checker: DestinationPermissionChecker,
    ): DestinationBindingStatus {
        if (binding == null || (binding.destinationDirectory.isNullOrBlank() && binding.storageTreeUri.isNullOrBlank())) {
            return DestinationBindingStatus.NO_BINDING
        }
        return if (checker.hasAccess(binding.destinationDirectory, binding.storageTreeUri)) {
            DestinationBindingStatus.VALID
        } else {
            DestinationBindingStatus.PERMISSION_DENIED
        }
    }

    fun exportProfileDefinition(profile: DownloadProfile): String =
        ProfileDefinitionCodec.export(profile)

    fun importProfileDefinition(rawJson: String): Result<DownloadProfile> =
        ProfileDefinitionCodec.decode(rawJson).mapCatching { profile ->
            require(profile.name.isNotBlank()) { "Profile name cannot be blank" }
            profile
        }
}

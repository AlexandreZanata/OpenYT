package com.openyt.data

import com.openyt.core.automation.SubscriptionRepository
import com.openyt.core.automation.SubscriptionRule
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightSubscriptions(database: OpenYtDatabase) : SubscriptionRepository {
    private val queries = database.downloadQueries
    override suspend fun load(): List<SubscriptionRule> = withContext(Dispatchers.Default) {
        queries.selectSubscriptions().executeAsList().mapNotNull {
            runCatching { Json.decodeFromString<SubscriptionRule>(it.definition_json) }.getOrNull()
        }
    }
    override suspend fun save(rule: SubscriptionRule) { withContext(Dispatchers.Default) { queries.saveSubscription(rule.id, Json.encodeToString(rule)) } }
    override suspend fun delete(id: String) { withContext(Dispatchers.Default) { queries.deleteSubscription(id) } }
}

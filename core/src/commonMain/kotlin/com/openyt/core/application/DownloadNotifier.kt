package com.openyt.core.application

import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask

interface DownloadNotifier {
    fun requestPermission()
    fun show(id: String, title: String, message: String, failed: Boolean)
}

/** Only transitions observed in this process notify; restored history is silent. */
class DownloadNotificationTracker {
    private var previous: Map<String, DownloadStatus>? = null

    fun update(tasks: List<DownloadTask>): List<DownloadTask> {
        val old = previous
        previous = tasks.associate { it.id to it.status }
        if (old == null) return emptyList()
        return tasks.filter {
            old[it.id] != it.status &&
                it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)
        }
    }
}

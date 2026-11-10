package com.openyt.core.application

import com.openyt.core.domain.DownloadTask

/** Runs after a download completes, for example to export to a SAF tree. */
fun interface CompletedDownloadHandler {
    suspend fun onCompleted(task: DownloadTask)
}

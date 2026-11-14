package com.openyt.core.application

import com.openyt.core.domain.DownloadRequest

interface DownloadExecutionGate {
    suspend fun canStart(request: DownloadRequest, reservedBytes: Long = 0): Boolean
    suspend fun canContinue(request: DownloadRequest): Boolean = canStart(request)
}

object FailClosedDownloadGate : DownloadExecutionGate {
    override suspend fun canStart(request: DownloadRequest, reservedBytes: Long): Boolean = request.options.executionConstraints == null
}

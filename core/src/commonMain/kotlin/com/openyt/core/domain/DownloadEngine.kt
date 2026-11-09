package com.openyt.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * The only entry point for inspecting and downloading media. Implementations are
 * platform specific and must not leak process arguments or tool output to callers.
 *
 * Progress percentages are reported in the 0..100 range.
 */
interface DownloadEngine {
    suspend fun inspect(url: String): EngineResult<MediaInfo>

    fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent>

    suspend fun cancel(downloadId: String)

    suspend fun capabilities(): EngineCapabilities

    fun close()
}

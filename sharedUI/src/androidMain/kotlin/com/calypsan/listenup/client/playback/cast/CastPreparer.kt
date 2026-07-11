package com.calypsan.listenup.client.playback.cast

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.BookId

/** Network cast inputs for a book: per-file absolute URL + format, plus the absolute cover URL. */
data class CastPrepared(
    val files: List<CastPreparedFile>,
    val coverUrlAbsolute: String?,
)

/**
 * Fetches the signed **network** URLs a Cast device needs. Casting always
 * re-prepares (even for downloaded books) because a Chromecast cannot read the
 * phone's local `file://` paths. Returns null on any failure → the caller stays
 * local (never stranded).
 */
class CastPreparer(
    private val prepareRepository: PlaybackPrepareRepository,
    private val serverConfig: ServerConfig,
) {
    suspend fun prepareForCast(bookId: BookId): CastPrepared? {
        val serverUrl = serverConfig.getServerUrl()?.value ?: return null
        return when (val result = prepareRepository.prepare(bookId)) {
            is AppResult.Success -> {
                val data = result.data
                CastPrepared(
                    files =
                        data.audioFiles.map {
                            CastPreparedFile(
                                fileId = it.fileId,
                                absoluteUrl = serverUrl + it.url,
                                format = it.format,
                            )
                        },
                    coverUrlAbsolute = data.coverUrl?.let { serverUrl + it },
                )
            }

            is AppResult.Failure -> {
                null
            }
        }
    }
}

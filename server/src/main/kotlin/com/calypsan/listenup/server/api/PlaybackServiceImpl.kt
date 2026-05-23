package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.UserStatsRepository
import io.ktor.http.encodeURLParameter
import kotlin.time.Clock

/**
 * [PlaybackService] implementation. Combines signed audio URLs from
 * [AudioUrlSigner] with the caller's resume position from
 * [PlaybackPositionRepository] to satisfy [prepare] in one round-trip.
 *
 * Caller identity is always taken from [principal] — never from request
 * fields — so userId cannot be spoofed across the wire.
 */
internal class PlaybackServiceImpl(
    private val bookRepository: BookRepository,
    private val audioFileLocator: AudioFileLocator,
    private val audioUrlSigner: AudioUrlSigner,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val listeningEventRepository: ListeningEventRepository,
    private val userStatsRepository: UserStatsRepository,
    private val principal: PrincipalProvider,
    private val clock: Clock = Clock.System,
) : PlaybackService {

    override suspend fun prepare(bookId: BookId): AppResult<PreparedPlayback> {
        val book = bookRepository.findById(bookId)
            ?: return AppResult.Failure(SyncError.NotFound(domain = "book", entityId = bookId.value))
        val userId = principal.current()?.userId?.value
            ?: return AppResult.Failure(SyncError.NotFound(domain = "principal", entityId = "none"))

        val audioFiles = book.audioFiles
            .sortedBy { it.index }
            .map { file ->
                val query = audioUrlSigner.signedQuery(
                    userId = userId,
                    bookId = bookId.value,
                    fileId = file.id,
                )
                PreparedAudioFile(
                    fileId = file.id,
                    index = file.index,
                    url = "/api/v1/audio/${bookId.value.encodeURLParameter()}/${file.id.encodeURLParameter()}?$query",
                    format = file.format,
                    durationMs = file.duration,
                    sizeBytes = file.size,
                )
            }

        val resumePosition = playbackPositionRepository.getPosition(userId, bookId.value)

        return AppResult.Success(
            PreparedPlayback(
                bookId = bookId.value,
                audioFiles = audioFiles,
                resumePosition = resumePosition,
            ),
        )
    }

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> {
        val userId = principal.current()?.userId?.value
            ?: return AppResult.Failure(SyncError.NotFound(domain = "principal", entityId = "none"))
        return AppResult.Success(playbackPositionRepository.getPosition(userId, bookId.value))
    }

    override suspend fun recordPosition(request: RecordPositionRequest): AppResult<PlaybackPositionSyncPayload> {
        val userId = principal.current()?.userId?.value
            ?: return AppResult.Failure(SyncError.NotFound(domain = "principal", entityId = "none"))
        return playbackPositionRepository.recordPosition(
            userId = userId,
            bookId = request.bookId,
            positionMs = request.positionMs,
            lastPlayedAt = request.lastPlayedAt,
            finished = request.finished,
            playbackSpeed = request.playbackSpeed,
            currentChapterId = request.currentChapterId,
        )
    }

    override suspend fun getStats(): AppResult<UserStatsSyncPayload?> {
        val userId = principal.current()?.userId?.value
            ?: return AppResult.Failure(SyncError.NotFound(domain = "principal", entityId = "none"))
        return AppResult.Success(userStatsRepository.getForUser(userId))
    }

    override suspend fun recordListeningEvent(request: RecordListeningEventRequest): AppResult<ListeningEventSyncPayload> {
        val userId = principal.current()?.userId?.value
            ?: return AppResult.Failure(SyncError.NotFound(domain = "principal", entityId = "none"))
        val now = clock.now().toEpochMilliseconds()
        val payload = ListeningEventSyncPayload(
            id = request.id,
            bookId = request.bookId,
            startPositionMs = request.startPositionMs,
            endPositionMs = request.endPositionMs,
            startedAt = request.startedAt,
            endedAt = request.endedAt,
            playbackSpeed = request.playbackSpeed,
            tz = request.tz,
            deviceLabel = request.deviceLabel,
            revision = 0L,
            updatedAt = now,
            createdAt = now,
            deletedAt = null,
        )
        return listeningEventRepository.upsert(payload, clientOpId = null, userId = userId)
    }

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): PlaybackServiceImpl =
        PlaybackServiceImpl(
            bookRepository = bookRepository,
            audioFileLocator = audioFileLocator,
            audioUrlSigner = audioUrlSigner,
            playbackPositionRepository = playbackPositionRepository,
            listeningEventRepository = listeningEventRepository,
            userStatsRepository = userStatsRepository,
            principal = principal,
            clock = clock,
        )
}

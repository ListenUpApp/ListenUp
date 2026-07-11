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
import com.calypsan.listenup.server.audio.CoverUrlSigner
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor
import io.ktor.http.encodeURLParameter
import kotlin.time.Clock

private val logger = loggerFor<PlaybackServiceImpl>()

/**
 * [PlaybackService] implementation. Combines signed audio URLs from
 * [AudioUrlSigner] with the caller's resume position from
 * [PlaybackPositionRepository] to satisfy [prepare] in one round-trip.
 *
 * Caller identity is always taken from [principal] — never from request
 * fields — so userId cannot be spoofed across the wire.
 *
 * [prepare] is gated through [BookAccessPolicy]: a book the caller cannot reach
 * answers `SyncError.NotFound`, never leaking its existence, metadata, signed
 * URLs, or chapter structure — consistent with `BookService.getBook`, the audio
 * route, and the cover route. ROOT/ADMIN bypass the filter.
 */
internal class PlaybackServiceImpl(
    private val bookRepository: BookRepository,
    private val audioFileLocator: AudioFileLocator,
    private val audioUrlSigner: AudioUrlSigner,
    private val coverUrlSigner: CoverUrlSigner,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val listeningEventRepository: ListeningEventRepository,
    private val userStatsRepository: UserStatsRepository,
    private val accessPolicy: BookAccessPolicy,
    private val principal: PrincipalProvider,
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) : PlaybackService {

    override suspend fun prepare(bookId: BookId): AppResult<PreparedPlayback> {
        val p = principal.current()
            ?: return AppResult.Failure(SyncError.NotFound(domain = "principal", entityId = "none"))
        val book = bookRepository.findById(bookId)
            ?: return AppResult.Failure(SyncError.NotFound(domain = "book", entityId = bookId.value))
        if (!accessPolicy.canAccess(p.userId.value, p.role, bookId.value)) {
            // Report a denied book as absent — never leak its existence, metadata, or chapters.
            return AppResult.Failure(SyncError.NotFound(domain = "book", entityId = bookId.value))
        }
        val userId = p.userId.value

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
        // Always mint — the route resolves covers lazily (embedded covers aren't persisted), so
        // guarding here would cost a resolution. No cover → route 404s → receiver shows no art.
        val coverUrl =
            "/api/v1/cover-cast/${bookId.value.encodeURLParameter()}?" +
                coverUrlSigner.signedQuery(userId = userId, bookId = bookId.value)

        return AppResult.Success(
            PreparedPlayback(
                bookId = bookId.value,
                audioFiles = audioFiles,
                resumePosition = resumePosition,
                coverUrl = coverUrl,
            ),
        )
    }

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> {
        val p = principal.current()
            ?: return AppResult.Failure(SyncError.NotFound(domain = "principal", entityId = "none"))
        if (!accessPolicy.canAccess(p.userId.value, p.role, bookId.value)) {
            return AppResult.Failure(SyncError.NotFound(domain = "book", entityId = bookId.value))
        }
        return AppResult.Success(playbackPositionRepository.getPosition(p.userId.value, bookId.value))
    }

    override suspend fun recordPosition(request: RecordPositionRequest): AppResult<PlaybackPositionSyncPayload> {
        val p = principal.current()
            ?: return AppResult.Failure(SyncError.NotFound(domain = "principal", entityId = "none"))
        if (!accessPolicy.canAccess(p.userId.value, p.role, request.bookId)) {
            return AppResult.Failure(SyncError.NotFound(domain = "book", entityId = request.bookId))
        }
        return playbackPositionRepository.recordPosition(
            userId = p.userId.value,
            bookId = request.bookId,
            positionMs = request.positionMs,
            lastPlayedAt = request.lastPlayedAt,
            finished = request.finished,
            playbackSpeed = request.playbackSpeed,
            currentChapterId = request.currentChapterId,
            maxPositionMs = request.maxPositionMs,
        )
    }

    override suspend fun getStats(): AppResult<UserStatsSyncPayload?> {
        val userId = principal.current()?.userId?.value
            ?: return AppResult.Failure(SyncError.NotFound(domain = "principal", entityId = "none"))
        return AppResult.Success(userStatsRepository.getForUser(userId))
    }

    override suspend fun recordListeningEvent(request: RecordListeningEventRequest): AppResult<ListeningEventSyncPayload> {
        val p = principal.current()
            ?: return AppResult.Failure(SyncError.NotFound(domain = "principal", entityId = "none"))
        if (!accessPolicy.canAccess(p.userId.value, p.role, request.bookId)) {
            return AppResult.Failure(SyncError.NotFound(domain = "book", entityId = request.bookId))
        }
        val userId = p.userId.value
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
        val result = listeningEventRepository.upsert(payload, clientOpId = null, userId = userId)
        // Best-effort: keep the user's home timezone current as they travel. Only the live
        // path (here) updates it — imports carry tz="UTC" and must never overwrite the real tz.
        if (result is AppResult.Success && request.tz.isNotBlank()) {
            runCatchingCancellable {
                suspendTransaction(sql) {
                    sql.usersQueries.updateTimezone(timezone = request.tz, id = userId)
                }
            }.onFailure { logger.warn(it) { "Failed to refresh timezone for user $userId — ignoring" } }
        }
        return result
    }

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): PlaybackServiceImpl =
        PlaybackServiceImpl(
            bookRepository = bookRepository,
            audioFileLocator = audioFileLocator,
            audioUrlSigner = audioUrlSigner,
            coverUrlSigner = coverUrlSigner,
            playbackPositionRepository = playbackPositionRepository,
            listeningEventRepository = listeningEventRepository,
            userStatsRepository = userStatsRepository,
            accessPolicy = accessPolicy,
            principal = principal,
            sql = sql,
            clock = clock,
        )
}

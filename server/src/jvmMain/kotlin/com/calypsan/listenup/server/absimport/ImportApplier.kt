package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * The apply stage of an ABS import: writes the staged listening progress into ListenUp through
 * [PlaybackPositionRepository.recordPosition] (one row per resolvable `(ABS user, ABS item)` pair),
 * imports each playback session as a [com.calypsan.listenup.api.sync.ListeningEventSyncPayload] via
 * [ListeningEventRepository.upsert], then recomputes every affected user's stats with
 * [UserStatsBackfillService.backfillFor].
 *
 * Apply is deliberately thin. Analyze already did the matching (persisted as `matches.json`) and the
 * admin already confirmed the user map and any per-item overrides (`mapping.json`). Apply re-reads
 * the ABS progress + session rows, resolves each against those two artifacts, and writes. It never
 * matches, never guesses, and never writes a row it can't fully resolve.
 *
 * **The progress + session writes run under [FirehoseSuppressed]** — a bulk import can produce an
 * arbitrarily large burst, and the lossy live-tail would overflow. The rows still commit and bump
 * the sync revision, so clients catch up via REST `pullSince`. The per-user [backfillFor][
 * UserStatsBackfillService.backfillFor] runs *after* (outside) the suppressed block, so the final
 * authoritative stats row publishes live.
 *
 * **Idempotency is inherited, not engineered.** `recordPosition` upserts on `(userId, bookId)` with
 * last-played-wins; sessions carry the stable `abs:<sessionId>` id, so a re-upsert no-ops the domain
 * fields (append-only) and the per-event stats hook fires only on first insert. The final backfill
 * is itself idempotent (it recomputes from the full event/position history). Re-applying the same
 * import therefore produces no duplicate events and leaves stats unchanged.
 *
 * Unmapped users and unresolved items are **skipped, not errored** — a partial library overlap is
 * the normal case, not a failure. They are counted in [ImportResult.skippedCount].
 */
class ImportApplier internal constructor(
    private val reader: AbsBackupReader,
    private val store: ImportStore,
    private val paths: ImportPaths,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val sessionConverter: SessionConverter,
    private val listeningEventRepository: ListeningEventRepository,
    private val statsBackfill: UserStatsBackfillService,
) {
    /**
     * Applies the confirmed import [importId], emitting [ImportEvent.Applying] progress through
     * [onEvent] and [ImportEvent.Applied] on success.
     *
     * A never-analyzed import (no `matches.json`) yields [ImportError.ImportNotFound]; a confirmed-
     * but-unmapped import (no `mapping.json`) or any unexpected failure yields
     * [ImportError.ApplyFailed].
     */
    suspend fun apply(
        importId: ImportId,
        onEvent: (ImportEvent) -> Unit,
    ): AppResult<ImportResult> =
        withContext(Dispatchers.IO) {
            val resolved =
                store.readMatches(importId)
                    ?: return@withContext AppResult.Failure(ImportError.ImportNotFound())
            val mapping =
                store.readMapping(importId)
                    ?: return@withContext AppResult.Failure(
                        ImportError.ApplyFailed(debugInfo = "No confirmed mapping for import ${importId.value}."),
                    )

            try {
                val effectiveBooks = effectiveBookMap(resolved.itemMatches, mapping.bookOverrides)
                val (progress, sessions) =
                    reader.open(paths.absDbFor(importId.value)).use { handle ->
                        handle.progress() to handle.playbackSessions()
                    }

                val affectedUsers = mutableSetOf<String>()
                // Bulk import: suppress the live firehose so the burst can't overflow the lossy
                // tail. Rows still commit + bump the revision, so clients catch up via REST.
                val result =
                    withContext(FirehoseSuppressed) {
                        val progressResult =
                            recordAll(progress, mapping.userMappings, effectiveBooks, affectedUsers, onEvent)
                        val sessionCounts =
                            recordSessions(sessions, mapping.userMappings, effectiveBooks, affectedUsers, onEvent)
                        ImportResult(
                            importedCount = progressResult.importedCount,
                            sessionsImported = sessionCounts.imported,
                            skippedCount = progressResult.skippedCount + sessionCounts.skipped,
                            perUser = progressResult.perUser,
                        )
                    }

                // Outside suppression: the authoritative per-user stats recompute publishes live.
                affectedUsers.forEach { statsBackfill.backfillFor(it) }

                store.markApplied(importId)
                onEvent(ImportEvent.Applied(result))
                AppResult.Success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AbsBackupReader.AbsReadException) {
                logger.error(e) { "ABS import apply failed reading the backup for import ${importId.value}" }
                onEvent(ImportEvent.Failed(reason = e.message ?: "Failed to read the ABS backup."))
                AppResult.Failure(ImportError.ApplyFailed(debugInfo = e.message))
            } catch (e: Exception) {
                logger.error(e) { "ABS import apply failed unexpectedly for import ${importId.value}" }
                onEvent(ImportEvent.Failed(reason = e.message ?: "Apply failed unexpectedly."))
                AppResult.Failure(ImportError.ApplyFailed(debugInfo = e.message))
            }
        }

    /**
     * Resolves the effective item→book map: the analyzed matches, with each non-null override
     * replacing the matched book and each **null override removing the item** (the admin chose to
     * skip it, even if it matched).
     */
    private fun effectiveBookMap(
        itemMatches: Map<AbsItemId, BookId>,
        bookOverrides: Map<AbsItemId, BookId?>,
    ): Map<AbsItemId, BookId> {
        val effective = itemMatches.toMutableMap()
        bookOverrides.forEach { (itemId, override) ->
            if (override == null) effective.remove(itemId) else effective[itemId] = override
        }
        return effective
    }

    /**
     * Records every resolvable progress row, counting imports per user and skips overall, and adds
     * each imported user to [affectedUsers] so their stats are backfilled (a finished position
     * refreshes `booksFinished` even when the user has no imported sessions).
     */
    private suspend fun recordAll(
        progress: List<AbsProgress>,
        userMappings: Map<AbsUserId, UserId>,
        effectiveBooks: Map<AbsItemId, BookId>,
        affectedUsers: MutableSet<String>,
        onEvent: (ImportEvent) -> Unit,
    ): ImportResult {
        val total = progress.size
        val perUser = mutableMapOf<UserId, Int>()
        var skipped = 0

        progress.forEachIndexed { index, row ->
            val targetUser = userMappings[AbsUserId(row.userId)]
            val targetBook = effectiveBooks[AbsItemId(row.itemId)]
            if (targetUser == null || targetBook == null) {
                skipped++
            } else {
                recordPosition(targetUser, targetBook, row)
                perUser[targetUser] = (perUser[targetUser] ?: 0) + 1
                affectedUsers += targetUser.value
            }
            onEvent(
                ImportEvent.Applying(
                    done = index + 1,
                    total = total,
                    currentItem = targetBook?.value ?: row.itemId,
                ),
            )
        }

        return ImportResult(
            importedCount = perUser.values.sum(),
            skippedCount = skipped,
            perUser = perUser,
        )
    }

    /**
     * Imports every resolvable playback session as a listening event (stable `abs:<id>`), counting
     * imports vs skips and adding each imported user to [affectedUsers]. The per-event stats hook
     * fires idempotently inside [ListeningEventRepository.upsert]; the final per-user backfill is
     * the authority.
     */
    private suspend fun recordSessions(
        sessions: List<AbsSession>,
        userMappings: Map<AbsUserId, UserId>,
        effectiveBooks: Map<AbsItemId, BookId>,
        affectedUsers: MutableSet<String>,
        onEvent: (ImportEvent) -> Unit,
    ): SessionCounts {
        val total = sessions.size
        var imported = 0
        var skipped = 0

        sessions.forEachIndexed { index, session ->
            val targetUser = userMappings[AbsUserId(session.userId)]
            val targetBook = effectiveBooks[AbsItemId(session.itemId)]
            if (targetUser == null || targetBook == null) {
                skipped++
            } else {
                listeningEventRepository.upsert(
                    value = sessionConverter.toEvent(session, targetBook.value),
                    clientOpId = null,
                    userId = targetUser.value,
                )
                imported++
                affectedUsers += targetUser.value
            }
            onEvent(
                ImportEvent.Applying(
                    done = index + 1,
                    total = total,
                    currentItem = targetBook?.value ?: session.itemId,
                    sessionsWritten = imported,
                ),
            )
        }

        return SessionCounts(imported = imported, skipped = skipped)
    }

    private data class SessionCounts(
        val imported: Int,
        val skipped: Int,
    )

    private suspend fun recordPosition(
        targetUser: UserId,
        targetBook: BookId,
        row: AbsProgress,
    ) {
        playbackPositionRepository.recordPosition(
            userId = targetUser.value,
            bookId = targetBook.value,
            positionMs = (row.currentTimeSeconds * MILLIS_PER_SECOND).toLong(),
            lastPlayedAt = row.lastUpdateMs,
            finished = row.isFinished || row.progress >= FINISHED_THRESHOLD,
            playbackSpeed = DEFAULT_PLAYBACK_SPEED,
            currentChapterId = null,
        )
    }

    private companion object {
        /** Belt-and-suspenders finished fallback: ABS `isFinished` is authoritative, this backs it up. */
        const val FINISHED_THRESHOLD = 0.99
        const val MILLIS_PER_SECOND = 1_000.0
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
    }
}

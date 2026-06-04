package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.import.ImportEvent
import com.calypsan.listenup.api.dto.import.ImportResult
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The apply stage of an ABS import: writes the staged listening progress into ListenUp through
 * [PlaybackPositionRepository.recordPosition], one row per resolvable `(ABS user, ABS item)` pair.
 *
 * Apply is deliberately thin. Analyze already did the matching (persisted as `matches.json`) and the
 * admin already confirmed the user map and any per-item overrides (`mapping.json`). Apply re-reads
 * the ABS progress rows, resolves each against those two artifacts, and records the position. It
 * never matches, never guesses, and never writes a row it can't fully resolve.
 *
 * **Idempotency is inherited, not engineered.** `recordPosition` upserts on `(userId, bookId)` with
 * last-played-wins, keyed on the ABS `lastUpdate` timestamp. Re-applying the same import therefore
 * re-fires identical writes that no-op against the existing rows, and a fresher local position (a
 * device that played past the imported point) is preserved because its `lastPlayedAt` is newer.
 *
 * Unmapped users and unresolved items are **skipped, not errored** — a partial library overlap is
 * the normal case, not a failure. They are counted in [ImportResult.skippedCount].
 */
class ImportApplier internal constructor(
    private val reader: AbsBackupReader,
    private val store: ImportStore,
    private val paths: ImportPaths,
    private val playbackPositionRepository: PlaybackPositionRepository,
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
                val progress =
                    reader.open(paths.absDbFor(importId.value)).use { handle -> handle.progress() }

                val result = recordAll(progress, mapping.userMappings, effectiveBooks, onEvent)
                store.markApplied(importId)
                onEvent(ImportEvent.Applied(result))
                AppResult.Success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AbsBackupReader.AbsReadException) {
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

    /** Records every resolvable progress row, counting imports per user and skips overall. */
    private suspend fun recordAll(
        progress: List<AbsProgress>,
        userMappings: Map<AbsUserId, UserId>,
        effectiveBooks: Map<AbsItemId, BookId>,
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
            }
            onEvent(ImportEvent.Applying(done = index + 1, total = total))
        }

        return ImportResult(
            importedCount = perUser.values.sum(),
            skippedCount = skipped,
            perUser = perUser,
        )
    }

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

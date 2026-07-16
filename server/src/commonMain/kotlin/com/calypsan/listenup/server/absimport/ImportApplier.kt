package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.server.io.canonicalize
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.services.ImportListeningEventWrite
import com.calypsan.listenup.server.services.ImportPositionWrite
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.StatsCascadeDeferred
import com.calypsan.listenup.server.services.StatsEvent
import com.calypsan.listenup.server.services.StatsRecorder
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private val logger = loggerFor<ImportApplier>()

/**
 * The apply stage of an ABS import: writes the staged listening progress into ListenUp through
 * [PlaybackPositionRepository.recordPosition] (one row per resolvable `(ABS user, ABS item)` pair),
 * imports each playback session as a [com.calypsan.listenup.api.sync.ListeningEventSyncPayload] via
 * [ListeningEventRepository.upsert], then recomputes every affected user's stats with one
 * [StatsRecorder.record] call carrying [StatsEvent.BulkRecompute].
 *
 * Apply is deliberately thin. Analyze already did the matching (persisted as `matches.json`) and the
 * admin already confirmed the user map and any per-item overrides (`mapping.json`). Apply re-reads
 * the ABS progress + session rows, resolves each against those two artifacts, and writes. It never
 * matches, never guesses, and never writes a row it can't fully resolve.
 *
 * **The progress + session writes run under [FirehoseSuppressed] and [StatsCascadeDeferred]** — a
 * bulk import can produce an arbitrarily large burst, and the lossy live-tail would overflow; the
 * per-row `user_stats` upsert and `public_profiles` refresh would also misfire repeatedly against
 * stale base totals mid-import. The source rows still commit and bump the sync revision, so clients
 * catch up via REST `pullSince`. The per-user [StatsEvent.BulkRecompute] runs *after* (outside) the
 * suppressed block, so the final authoritative stats row publishes live.
 *
 * **On success, apply broadcasts [SyncControl.LibraryDataChanged]** to every connected client. The
 * suppressed burst never reached the live tail, so without this nudge other clients would only see
 * the imported positions/sessions on their next reconnect (app restart). The nudge makes them
 * re-derive each domain via digest reconciliation — the same convergence path the firehose-suppressed
 * scan relies on — so they pick up the import live.
 *
 * **Idempotency is inherited, not engineered.** `recordPosition` upserts on `(userId, bookId)` with
 * last-played-wins; sessions carry the stable `abs:<sessionId>` id, so a re-upsert no-ops the domain
 * fields (append-only) and the per-event stats hook fires only on first insert. The final
 * [StatsEvent.BulkRecompute] is itself idempotent (it recomputes from the full event/position
 * history). Re-applying the same import therefore produces no duplicate events and leaves stats
 * unchanged.
 *
 * **Interrupted applies self-heal.** Apply persists an `.applying` marker before writing any row;
 * a crash or failure mid-burst leaves that marker without the completion `.applied` marker. At
 * server boot [InterruptedImportResumer] re-runs every such import — idempotency makes the re-run
 * converge the partial state (rows complete, stats recompute, clients get the owed nudge).
 *
 * Unmapped users and unresolved items are **skipped, not errored** — a partial library overlap is
 * the normal case, not a failure. A mapped user's books that aren't in this library are surfaced
 * as [ImportResult.booksNotInLibrary]; unmapped-user history is excluded by the admin, not counted.
 */
class ImportApplier internal constructor(
    private val reader: AbsBackupReader,
    private val store: ImportStore,
    private val paths: ImportPaths,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val sessionConverter: SessionConverter,
    private val listeningEventRepository: ListeningEventRepository,
    private val statsRecorder: StatsRecorder,
    private val changeBus: ChangeBus,
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
        withContext(fileIoDispatcher) {
            val resolved =
                store.readMatches(importId)
                    ?: return@withContext AppResult.Failure(ImportError.ImportNotFound())
            val mapping =
                store.readMapping(importId)
                    ?: return@withContext AppResult.Failure(
                        ImportError.ApplyFailed(debugInfo = "No confirmed mapping for import ${importId.value}."),
                    )

            try {
                // Record that writing is about to start — after the guards (so a never-analyzed /
                // never-mapped import can never be flagged interrupted) and before any DB write or the
                // backup read (so even a read crash is re-driven at boot). Inside the try so a
                // marker-write failure surfaces as a typed ApplyFailed, not an escaping throwable. The
                // marker persists across a crash or failure and is only cleared by markApplied, so
                // ".applying without .applied" is the durable signature of an interrupted apply.
                store.markApplying(importId)

                val effectiveBooks = effectiveBookMap(resolved.itemMatches, mapping.bookOverrides)
                val (progress, sessions) =
                    reader
                        .open(canonicalize(paths.absDbFor(importId.value)).toString())
                        .use { handle ->
                            handle.progress() to handle.playbackSessions()
                        }

                val affectedUsers = mutableSetOf<String>()
                // The honest "couldn't import" count: distinct books a mapped user has history for
                // that aren't in this library. Computed once over the raw data — independent of the
                // write loops, which simply skip the unresolvable rows.
                val booksNotInLibrary =
                    mappedUserBooksNotInLibrary(progress, sessions, mapping.userMappings, effectiveBooks)
                // Earliest imported session start per raw (ABS user, ABS item) — used to date the
                // imported STARTED_BOOK activity strictly before the book's own sessions, so the
                // activity feed doesn't show a start AFTER the listening it kicked off.
                val earliestSessionStartMs: Map<Pair<String, String>, Long> =
                    sessions
                        .filter { it.startedAtMs > 0L }
                        .groupBy({ it.userId to it.itemId }, { it.startedAtMs })
                        .mapValues { (_, starts) -> starts.min() }
                // Bulk import: suppress the live firehose so the burst can't overflow the lossy
                // tail, and defer the per-row stats cascade so the importer doesn't refresh
                // user_stats/public_profiles once per row. Source rows still commit + bump the
                // revision, so clients catch up via REST.
                val result =
                    withContext(FirehoseSuppressed + StatsCascadeDeferred) {
                        val perUser =
                            recordAll(
                                progress,
                                mapping.userMappings,
                                effectiveBooks,
                                earliestSessionStartMs,
                                affectedUsers,
                                onEvent,
                            )
                        val sessionsImported =
                            recordSessions(sessions, mapping.userMappings, effectiveBooks, affectedUsers, onEvent)
                        ImportResult(
                            importedCount = perUser.values.sum(),
                            sessionsImported = sessionsImported,
                            booksNotInLibrary = booksNotInLibrary,
                            perUser = perUser,
                        )
                    }

                // Outside deferral: one BulkRecompute per affected user — the full rebuild +
                // projection refresh that StatsCascadeDeferred skipped per row above. The
                // projection is what the leaderboard reads and what the activity-feed /
                // book-detail readers surfaces resolve identities through, so a backfill that
                // skipped the refresh left those surfaces stale (or empty) until a server
                // restart rebuilt the projection. Both publish live.
                affectedUsers.forEach { userId -> statsRecorder.record(StatsEvent.BulkRecompute(userId)) }

                store.markApplied(importId)

                // The progress + session burst was firehose-suppressed, so it never reached the
                // live tail. Nudge every connected client to re-derive its domains via digest
                // reconciliation, so other devices pick up the imported positions/sessions live
                // instead of only on their next reconnect (app restart).
                changeBus.broadcastControl(SyncControl.LibraryDataChanged)

                onEvent(ImportEvent.Applied(result))
                AppResult.Success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AbsBackupReader.AbsReadException) {
                logger.error(e) { "ABS import apply failed reading the backup for import ${importId.value}" }
                broadcastLibraryDataChangedBestEffort()
                onEvent(ImportEvent.Failed(reason = e.message ?: "Failed to read the ABS backup."))
                AppResult.Failure(ImportError.ApplyFailed(debugInfo = e.message))
            } catch (e: Exception) {
                logger.error(e) { "ABS import apply failed unexpectedly for import ${importId.value}" }
                broadcastLibraryDataChangedBestEffort()
                onEvent(ImportEvent.Failed(reason = e.message ?: "Apply failed unexpectedly."))
                AppResult.Failure(ImportError.ApplyFailed(debugInfo = e.message))
            }
        }

    /**
     * Post-failure `LibraryDataChanged` nudge for [apply]'s failure branches. `recordAll` /
     * `recordSessions` commit in **chunked** transactions, so a failure partway through can leave
     * already-committed rows sitting above every client cursor with no live signal (the same
     * FirehoseSuppressed gap the success path's broadcast closes) — unconditional-on-failure is
     * the safe default here: a spurious nudge on a zero-chunk-committed failure costs one wasted
     * reconcile pass, whereas skipping it risks stranding real data until a client restart.
     * Best-effort: a broadcast failure must never mask the original apply failure, so it is caught
     * and logged rather than propagated. [CancellationException] still needs to win — the coroutine
     * is being torn down, so nothing further should run.
     */
    private suspend fun broadcastLibraryDataChangedBestEffort() {
        try {
            changeBus.broadcastControl(SyncControl.LibraryDataChanged)
        } catch (broadcastError: CancellationException) {
            throw broadcastError
        } catch (broadcastError: Exception) {
            logger.warn(broadcastError) { "post-import-failure LibraryDataChanged broadcast failed" }
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
     * Distinct ABS items a *mapped* user has history for (progress or sessions) but which aren't in
     * this ListenUp library — the honest "couldn't import: not in your library" count. Unmapped-user
     * history is the admin's deliberate review-step exclusion, not a "not found", so it never counts.
     */
    private fun mappedUserBooksNotInLibrary(
        progress: List<AbsProgress>,
        sessions: List<AbsSession>,
        userMappings: Map<AbsUserId, UserId>,
        effectiveBooks: Map<AbsItemId, BookId>,
    ): Int {
        val mappedItems =
            progress.asSequence().filter { AbsUserId(it.userId) in userMappings }.map { AbsItemId(it.itemId) } +
                sessions.asSequence().filter { AbsUserId(it.userId) in userMappings }.map { AbsItemId(it.itemId) }
        return mappedItems.filter { it !in effectiveBooks }.toSet().size
    }

    /**
     * Records every resolvable progress row through the batched
     * [PlaybackPositionRepository.recordAllForImport] write, counting imports per user and adding each
     * imported user to [affectedUsers] so their stats are backfilled (a finished position refreshes
     * `booksFinished` even when the user has no imported sessions). Returns the per-user
     * written-position counts.
     *
     * [earliestSessionStartMs] (keyed on raw `(ABS user, ABS item)`) dates each imported
     * `STARTED_BOOK` activity strictly before that book's earliest imported session.
     *
     * Progress frames are throttled: one every [APPLY_EVENT_INTERVAL] rows during the resolve scan,
     * plus an always-emitted final frame reporting `done == total`. A full history import used to emit
     * one SSE frame per row (including skipped rows); the batched write makes that frequency pointless.
     */
    private suspend fun recordAll(
        progress: List<AbsProgress>,
        userMappings: Map<AbsUserId, UserId>,
        effectiveBooks: Map<AbsItemId, BookId>,
        earliestSessionStartMs: Map<Pair<String, String>, Long>,
        affectedUsers: MutableSet<String>,
        onEvent: (ImportEvent) -> Unit,
    ): Map<UserId, Int> {
        val total = progress.size
        val perUser = mutableMapOf<UserId, Int>()
        val writes = mutableListOf<ImportPositionWrite>()
        var lastItem: String? = null

        progress.forEachIndexed { index, row ->
            val targetUser = userMappings[AbsUserId(row.userId)]
            val targetBook = effectiveBooks[AbsItemId(row.itemId)]
            lastItem = targetBook?.value ?: row.itemId
            if (targetUser != null && targetBook != null) {
                writes +=
                    ImportPositionWrite(
                        userId = targetUser.value,
                        bookId = targetBook.value,
                        positionMs = (row.currentTimeSeconds * MILLIS_PER_SECOND).toLong(),
                        lastPlayedAt = row.lastUpdateMs,
                        finished = row.isFinished || row.progress >= FINISHED_THRESHOLD,
                        playbackSpeed = DEFAULT_PLAYBACK_SPEED,
                        currentChapterId = null,
                        // Date the imported start strictly before this book's earliest session, and never
                        // later than the un-fixed value. Null when the book has no imported sessions →
                        // live behavior (lastPlayedAt).
                        startedBookOccurredAt =
                            earliestSessionStartMs[row.userId to row.itemId]
                                ?.let { minOf(row.lastUpdateMs, it - 1) },
                    )
                perUser[targetUser] = (perUser[targetUser] ?: 0) + 1
                affectedUsers += targetUser.value
            }
            if ((index + 1) % APPLY_EVENT_INTERVAL == 0) {
                onEvent(ImportEvent.Applying(done = index + 1, total = total, currentItem = lastItem ?: row.itemId))
            }
        }

        playbackPositionRepository.recordAllForImport(writes)

        if (total > 0) {
            onEvent(ImportEvent.Applying(done = total, total = total, currentItem = lastItem ?: ""))
        }
        return perUser
    }

    /**
     * Imports every resolvable playback session as a listening event (stable `abs:<id>`) through the
     * batched [ListeningEventRepository.upsertAllForImport] write, and adds each imported user to
     * [affectedUsers]. The per-event `ListeningSessionClosed` stats hook fires idempotently on first
     * insert; the final per-user backfill is the authority. Returns the number of sessions written.
     *
     * Progress frames are throttled like [recordAll]: one every [APPLY_EVENT_INTERVAL] rows during the
     * resolve scan, plus an always-emitted final frame reporting `done == total` and the cumulative
     * `sessionsWritten` tally.
     */
    private suspend fun recordSessions(
        sessions: List<AbsSession>,
        userMappings: Map<AbsUserId, UserId>,
        effectiveBooks: Map<AbsItemId, BookId>,
        affectedUsers: MutableSet<String>,
        onEvent: (ImportEvent) -> Unit,
    ): Int {
        val total = sessions.size
        val writes = mutableListOf<ImportListeningEventWrite>()
        var lastItem: String? = null

        sessions.forEachIndexed { index, session ->
            val targetUser = userMappings[AbsUserId(session.userId)]
            val targetBook = effectiveBooks[AbsItemId(session.itemId)]
            lastItem = targetBook?.value ?: session.itemId
            if (targetUser != null && targetBook != null) {
                writes +=
                    ImportListeningEventWrite(
                        userId = targetUser.value,
                        event = sessionConverter.toEvent(session, targetBook.value),
                    )
                affectedUsers += targetUser.value
            }
            if ((index + 1) % APPLY_EVENT_INTERVAL == 0) {
                onEvent(
                    ImportEvent.Applying(
                        done = index + 1,
                        total = total,
                        currentItem = lastItem ?: session.itemId,
                        sessionsWritten = writes.size,
                    ),
                )
            }
        }

        listeningEventRepository.upsertAllForImport(writes)

        val imported = writes.size
        if (total > 0) {
            onEvent(
                ImportEvent.Applying(
                    done = total,
                    total = total,
                    currentItem = lastItem ?: "",
                    sessionsWritten = imported,
                ),
            )
        }
        return imported
    }

    private companion object {
        /** Belt-and-suspenders finished fallback: ABS `isFinished` is authoritative, this backs it up. */
        const val FINISHED_THRESHOLD = 0.99
        const val MILLIS_PER_SECOND = 1_000.0
        const val DEFAULT_PLAYBACK_SPEED = 1.0f

        /** Emit one [ImportEvent.Applying] progress frame per this many resolved rows, plus a final frame. */
        const val APPLY_EVENT_INTERVAL = 50
    }
}

package com.calypsan.listenup.server.sidecar

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.services.readBookPayloads
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import kotlin.time.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

private val logger = loggerFor<SidecarWriter>()

/** Default debounce window: a burst of curation edits collapses to one write ~5 s after the last. */
private const val DEFAULT_DEBOUNCE_MS = 5_000L

/** The `server_settings` key gating sidecar writes. Absent = enabled (spec: on by default). */
const val SIDECAR_WRITES_ENABLED_KEY = "sidecar_writes_enabled"

/**
 * The write-through half of the `listenup.json` sidecar (Foundation Trio Phase 2): curation
 * edits call [markDirty], a per-book debounce window collapses bursts (the FolderWatcher
 * coalescing idiom — cancel-and-restart per book), and the flush assembles the book's current
 * DB state via [SidecarAssembler], writes it through [LibraryWriteBroker] (watcher-suppressed,
 * atomic), and records the returned content hash in [SidecarWriteStateRepository] — the
 * round-trip discriminator the read side uses to skip self-written files.
 *
 * Failure is parked, never lost: a broker failure (unwritable mount) leaves the book in a
 * pending set that [retryPending] — called by the periodic `SidecarRetryTask` — flushes when
 * the mount recovers. Gated by the [SIDECAR_WRITES_ENABLED_KEY] admin setting; absent = enabled.
 *
 * **Hook coverage (v1):** [markDirty] fires from exactly four curation mutations on
 * `BookServiceImpl` — `updateBook`, `setBookContributors`, `setBookChapters`, and
 * `setBookSeries`. Tag and genre edits (`TagServiceImpl` junction writes, `setBookGenres`)
 * do NOT trigger a rewrite on their own: a tag or genre change reaches the sidecar on the
 * book's next curation flush, not at edit time. The flush always reads the CURRENT tag and
 * genre state, so nothing is lost — only delayed until the next hooked edit.
 */
class SidecarWriter(
    private val db: ListenUpDatabase,
    private val assembler: SidecarAssembler,
    private val broker: LibraryWriteBroker,
    private val writeState: SidecarWriteStateRepository,
    private val settings: ServerSettingsRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private val lock = SynchronizedObject()
    private val pendingJobs = mutableMapOf<String, Job>()
    private val pendingRetry = mutableSetOf<String>()

    /**
     * Signals that [bookId]'s curation changed. Schedules (or restarts) the book's debounce
     * window; the actual write happens [debounceMs] later, against whatever the DB holds then.
     * Call AFTER the curating transaction commits — the flush re-reads the aggregate, so a
     * pre-commit call would snapshot stale state only if the window elapsed before commit
     * (it can't in practice, but the contract is post-commit anyway).
     */
    fun markDirty(bookId: String) {
        synchronized(lock) {
            pendingJobs[bookId]?.cancel()
            pendingJobs[bookId] =
                scope.launch {
                    delay(debounceMs)
                    try {
                        flush(bookId)
                    } finally {
                        // Identity-conditional removal: a cancelled job's finally races the
                        // markDirty that replaced it — an unconditional remove could evict the
                        // NEWER job, breaking coalescing and awaitQuiescent. Only THIS job may
                        // remove itself.
                        val self = coroutineContext[Job]
                        synchronized(lock) {
                            if (pendingJobs[bookId] === self) pendingJobs.remove(bookId)
                        }
                    }
                }
        }
    }

    /**
     * Re-flushes every book whose last write failed (unwritable mount). Called by the periodic
     * `SidecarRetryTask`; safe to call any time — a book that fails again just stays parked.
     */
    suspend fun retryPending() {
        val toRetry = synchronized(lock) { pendingRetry.toList() }
        for (bookId in toRetry) {
            flush(bookId)
        }
    }

    /** Books whose last flush failed and await [retryPending]. Exposed for tests and diagnostics. */
    fun pendingBookIds(): Set<String> = synchronized(lock) { pendingRetry.toSet() }

    /** Waits for every currently-scheduled debounce job to finish — test-support only. */
    internal suspend fun awaitQuiescent() {
        val jobs = synchronized(lock) { pendingJobs.values.toList() }
        jobs.forEach { it.join() }
    }

    /**
     * One write attempt for [bookId]: setting gate → aggregate read → assemble → broker write →
     * hash record. Never throws (short of cancellation) — an unexpected failure is logged and
     * the book parked for retry, because a sidecar write must never break the edit that
     * triggered it.
     */
    private suspend fun flush(bookId: String) {
        try {
            if (!writesEnabled()) return
            val book = readBook(bookId) ?: return // deleted mid-window — nothing to write
            val sidecar = assembler.assemble(book, tagNamesFor(bookId))
            val bytes = SidecarJson.serialize(sidecar)
            val bookDir = resolveBookDir(bookId) ?: return
            when (val written = broker.writeFile(Path(bookDir, SIDECAR_FILENAME), bytes)) {
                is AppResult.Success -> {
                    writeState.save(
                        bookId = bookId,
                        contentHashHex = written.data.contentHashHex,
                        writtenAtMs = clock.now().toEpochMilliseconds(),
                    )
                    synchronized(lock) { pendingRetry.remove(bookId) }
                }

                is AppResult.Failure -> {
                    logger.warn { "sidecar write parked for retry book=$bookId err=${written.error.code}" }
                    synchronized(lock) { pendingRetry.add(bookId) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "sidecar flush failed for book=$bookId — parked for retry" }
            synchronized(lock) { pendingRetry.add(bookId) }
        }
    }

    private suspend fun writesEnabled(): Boolean =
        settings.getValue(SIDECAR_WRITES_ENABLED_KEY)?.toBooleanStrictOrNull() ?: true

    private suspend fun readBook(bookId: String): BookSyncPayload? =
        suspendTransaction(db) { db.readBookPayloads(listOf(bookId)).firstOrNull() }

    /** The book's live tag names — the junction rows resolved to their tags' display names. */
    private suspend fun tagNamesFor(bookId: String): List<String> =
        suspendTransaction(db) {
            val tagIds =
                db.bookTagsQueries
                    .selectByBookId(bookId)
                    .executeAsList()
                    .map { it.tag_id }
            if (tagIds.isEmpty()) {
                emptyList()
            } else {
                db.tagsQueries
                    .selectByIds(tagIds)
                    .executeAsList()
                    .map { it.name }
            }
        }

    /**
     * The book's absolute directory: `<library_folders.root_path>/<books.root_rel_path>` —
     * the same join [com.calypsan.listenup.server.audio.AudioFileLocator] resolves file paths
     * through. Null when the book or its folder row is gone.
     */
    private suspend fun resolveBookDir(bookId: String): Path? =
        suspendTransaction(db) {
            val bookRow =
                db.booksQueries.selectById(bookId).executeAsOneOrNull()
                    ?: return@suspendTransaction null
            val folderRoot =
                db.libraryFoldersQueries
                    .selectById(bookRow.folder_id)
                    .executeAsOneOrNull()
                    ?.root_path ?: return@suspendTransaction null
            Path(folderRoot, bookRow.root_rel_path)
        }

    companion object {
        /** The sidecar's on-disk filename, colocated with the book's audio files. */
        const val SIDECAR_FILENAME = "listenup.json"
    }
}

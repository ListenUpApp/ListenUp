package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.AbsItemRef
import com.calypsan.listenup.api.dto.imports.AbsUserMatch
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.services.LibraryRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.io.path.exists

/**
 * Read-only analyze stage of an ABS import: parse the staged backup, match its users and
 * progress-bearing items against the ListenUp library, and produce the [ImportAnalysis] preview
 * (plus the server-internal `matches.json` apply needs).
 *
 * Analyze never writes progress and never auto-resolves a doubtful match — ambiguous and unmatched
 * items are surfaced for admin review. Only items that actually have a progress row are matched:
 * matching the whole library would be wasteful and pointless, since an item with no listening history
 * has nothing to import.
 */
class ImportAnalyzer internal constructor(
    private val reader: AbsBackupReader,
    private val store: ImportStore,
    private val paths: ImportPaths,
    private val bookMatcher: BookMatcher,
    private val userMatcher: UserMatcher,
    private val libraryRegistry: LibraryRegistry,
    private val db: Database,
) {
    /**
     * Analyzes the staged import [importId], emitting [ImportEvent]s through [onEvent] as it goes.
     *
     * Returns the [ImportAnalysis] preview on success. A missing import directory / ABS database
     * yields [ImportError.ImportNotFound]; a malformed or non-ABS database yields
     * [ImportError.AnalysisFailed]. The resolved item→book matches are persisted via
     * [ImportStore.writeMatches] so apply doesn't re-run matching.
     */
    suspend fun analyze(
        importId: ImportId,
        onEvent: (ImportEvent) -> Unit,
    ): AppResult<ImportAnalysis> =
        withContext(Dispatchers.IO) {
            val absDb = paths.absDbFor(importId.value)
            if (!absDb.exists()) {
                return@withContext AppResult.Failure(ImportError.ImportNotFound())
            }
            try {
                onEvent(ImportEvent.Parsing)
                val absData =
                    reader.open(absDb).use { handle ->
                        AbsReadResult(
                            handle.users(),
                            handle.bookItems(),
                            handle.progress(),
                            handle.playbackSessions(),
                        )
                    }

                val libraryId = libraryRegistry.currentLibrary()
                val listenupUsers = loadMatchableUsers()

                val userMatches = absData.users.map { userMatcher.match(it, listenupUsers) }

                val itemsWithProgress = itemsWithProgress(absData.items, absData.progress)
                val matches = matchItems(itemsWithProgress, libraryId, onEvent)

                val importableSessionCount = importableSessionCount(absData.sessions, matches)
                val analysis = assembleAnalysis(userMatches, matches, importableSessionCount)
                store.writeAnalysis(importId, analysis)
                store.writeMatches(importId, resolvedFrom(userMatches, matches))

                onEvent(ImportEvent.Analyzed(store.getImport(importId)!!))
                AppResult.Success(analysis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AbsBackupReader.AbsReadException) {
                onEvent(ImportEvent.Failed(reason = e.message ?: "Failed to read the ABS backup."))
                AppResult.Failure(ImportError.AnalysisFailed(debugInfo = e.message))
            } catch (e: Exception) {
                onEvent(ImportEvent.Failed(reason = e.message ?: "Analysis failed unexpectedly."))
                AppResult.Failure(ImportError.AnalysisFailed(debugInfo = e.message))
            }
        }

    /** Loads the non-deleted ListenUp users once, reduced to the matcher's fields. */
    private suspend fun loadMatchableUsers(): List<MatchableUser> =
        suspendTransaction(db) {
            UserTable
                .select(UserTable.id, UserTable.email, UserTable.displayName)
                .where { UserTable.deletedAt.isNull() }
                .map {
                    MatchableUser(
                        id = UserId(it[UserTable.id].value),
                        email = it[UserTable.email],
                        displayName = it[UserTable.displayName],
                    )
                }
        }

    /** The subset of [items] that have at least one progress row (only these need matching). */
    private fun itemsWithProgress(
        items: List<AbsItem>,
        progress: List<AbsProgress>,
    ): List<AbsItem> {
        val progressed = progress.mapTo(mutableSetOf()) { it.itemId }
        return items.filter { it.id in progressed }
    }

    /** Matches each progress-bearing item, emitting [ImportEvent.Matching] progress. */
    private suspend fun matchItems(
        items: List<AbsItem>,
        libraryId: LibraryId,
        onEvent: (ImportEvent) -> Unit,
    ): List<ItemMatch> {
        val total = items.size
        return items.mapIndexed { index, item ->
            val match = bookMatcher.match(item, libraryId)
            onEvent(ImportEvent.Matching(done = index + 1, total = total))
            ItemMatch(item, match.bookId, match.tier)
        }
    }

    /** Builds the contract preview: per-tier counts + the ambiguous / unmatched ref lists. */
    private fun assembleAnalysis(
        userMatches: List<AbsUserMatch>,
        matches: List<ItemMatch>,
        importableSessionCount: Int,
    ): ImportAnalysis =
        ImportAnalysis(
            userMatches = userMatches,
            bookMatchCounts = matches.groupingBy { it.tier }.eachCount(),
            ambiguous = matches.filter { it.tier == MatchTier.AMBIGUOUS }.map { it.item.toRef() },
            unmatched = matches.filter { it.tier == MatchTier.UNMATCHED }.map { it.item.toRef() },
            importableSessionCount = importableSessionCount,
        )

    /** Counts playback sessions whose item resolved to a confident book — the importable estimate. */
    private fun importableSessionCount(
        sessions: List<AbsSession>,
        matches: List<ItemMatch>,
    ): Int {
        val resolvedItems = matches.mapNotNull { m -> m.bookId?.let { m.item.id } }.toSet()
        return sessions.count { it.itemId in resolvedItems }
    }

    /** Persists only the confidently-resolved item→book pairs; ambiguous/unmatched are omitted. */
    private fun resolvedFrom(
        userMatches: List<AbsUserMatch>,
        matches: List<ItemMatch>,
    ): ImportStore.ResolvedImport =
        ImportStore.ResolvedImport(
            itemMatches =
                matches
                    .mapNotNull { m -> m.bookId?.let { AbsItemId(m.item.id) to it } }
                    .toMap(),
            userMatches = userMatches,
        )

    private data class AbsReadResult(
        val users: List<AbsUser>,
        val items: List<AbsItem>,
        val progress: List<AbsProgress>,
        val sessions: List<AbsSession>,
    )

    private data class ItemMatch(
        val item: AbsItem,
        val bookId: BookId?,
        val tier: MatchTier,
    )
}

/** Projects an ABS item into the wire-facing reference shown in ambiguous/unmatched preview lists. */
private fun AbsItem.toRef(): AbsItemRef =
    AbsItemRef(
        absItemId = AbsItemId(id),
        title = title,
        asin = asin,
        isbn = isbn,
        relPath = relPath,
    )

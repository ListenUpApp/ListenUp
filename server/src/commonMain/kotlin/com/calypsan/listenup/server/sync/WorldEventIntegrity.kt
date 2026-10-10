package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.domain.storyworld.MentionTokens
import com.calypsan.listenup.domain.storyworld.WorldEventRules
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase

/** A world-event home: exactly one of [seriesId] / [bookId]. */
internal data class WorldHome(
    val seriesId: String?,
    val bookId: String?,
)

/**
 * The checks a world-event write makes against the rest of the database: that its anchor is a live book of
 * its world, that its subject and object are entities of its world of the kinds its type allows, and which
 * entities its text names. Every method must run inside the write's transaction, so it decides against the
 * same rows the write replaces.
 */
internal class WorldEventIntegrity(
    private val db: ListenUpDatabase,
) {
    /**
     * [WorldEventError.InvalidAnchor] unless [bookId] is null or a live book of [home]: the home book itself,
     * or a live book directly in the home series.
     */
    fun anchorProblem(
        bookId: String?,
        home: WorldHome,
    ): WorldEventError.InvalidAnchor? {
        if (bookId == null) return null
        val inWorld =
            when {
                home.bookId != null -> {
                    bookId == home.bookId && db.worldEventsQueries.isLiveBook(bookId).executeAsOne()
                }

                home.seriesId != null -> {
                    db.worldEventsQueries
                        .isLiveBookInSeries(
                            book_id = bookId,
                            series_id = home.seriesId,
                        ).executeAsOne()
                }

                else -> {
                    false
                }
            }
        return if (inWorld) null else WorldEventError.InvalidAnchor(debugInfo = "book=$bookId")
    }

    /**
     * Why [subjectId] / [objectId] can't fill [type]'s parts in [home], or null: an entity of another home — or,
     * when [requireLive], a deleted one — is [WorldEventError.EntityNotInWorld]; a wrong kind is
     * [WorldEventError.WrongEntityKind]. A revert passes `requireLive = false`: undo must not be blocked by a
     * later delete.
     */
    fun participantProblem(
        type: WorldEventType,
        subjectId: String?,
        objectId: String?,
        home: WorldHome,
        requireLive: Boolean,
    ): WorldEventError? {
        val subjectKind = subjectId?.let { kindInWorld(it, home, requireLive) ?: return notInWorld(it) }
        val objectKind = objectId?.let { kindInWorld(it, home, requireLive) ?: return notInWorld(it) }
        return WorldEventRules.kindProblem(type, subjectKind, objectKind)
    }

    /** The entities of [event]'s world (deleted ones included) that its text, subject and object name, sorted. */
    fun mentionIds(event: WorldEventSyncPayload): List<String> {
        val candidates =
            (
                MentionTokens.extractMentionIds(
                    event.text,
                ) + setOfNotNull(event.subjectEntityId, event.objectEntityId)
            ).toList()
        if (candidates.isEmpty()) return emptyList()
        return candidates
            .chunked(SQLITE_IN_CHUNK)
            .flatMap { chunk ->
                when {
                    event.homeBookId != null -> {
                        db.worldEventsQueries
                            .selectEntityIdsInBook(
                                chunk,
                                event.homeBookId,
                            ).executeAsList()
                    }

                    event.homeSeriesId != null -> {
                        db.worldEventsQueries
                            .selectEntityIdsInSeries(
                                chunk,
                                event.homeSeriesId,
                            ).executeAsList()
                    }

                    else -> {
                        emptyList()
                    }
                }
            }.sorted()
    }

    private fun kindInWorld(
        entityId: String,
        home: WorldHome,
        requireLive: Boolean,
    ): EntityKind? {
        val row = db.entitiesQueries.selectById(entityId).executeAsOneOrNull() ?: return null
        val usable =
            row.home_series_id == home.seriesId && row.home_book_id == home.bookId &&
                (!requireLive || row.deleted_at == null)
        return if (usable) EntityKind.fromName(row.kind.uppercase()) else null
    }

    private fun notInWorld(entityId: String) = WorldEventError.EntityNotInWorld(debugInfo = "entity=$entityId")

    private companion object {
        /** Bound variables per IN list, under SQLite's historical 999 limit with headroom. */
        const val SQLITE_IN_CHUNK = 900
    }
}

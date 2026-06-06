package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ActivityService
import com.calypsan.listenup.api.dto.activity.ActivityEvent
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.services.ActivityRepository
import com.calypsan.listenup.server.services.ActivityRow
import com.calypsan.listenup.server.sync.PublicProfileRepository

/**
 * [ActivityService] implementation — the ACL-filtered, paginated activity-feed read surface.
 *
 * Resolves the authenticated caller from [principal] (never from request fields) and filters every
 * page through [BookAccessPolicy] for that viewer. The hard invariant: a viewer must never learn
 * about activity on a book they cannot access. Book-bearing rows for inaccessible books are
 * dropped; non-book rows (shelf/milestone/user-joined) always pass. ROOT/ADMIN (unconstrained
 * access set) see every book-bearing row.
 *
 * Because filtering happens after the store reads a raw page, a naive single page could return
 * fewer than [feed]'s requested `limit` even when enough accessible rows exist further back. To
 * keep pages full, [feed] overfetches ([OVERFETCH]× the limit) and re-pages by the trailing
 * `created_at` cursor until it has `limit` visible rows or the log is exhausted.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal; the Koin
 * singleton carries an unscoped placeholder [PrincipalProvider] that throws (fail-loud) if ever
 * invoked, so a route that forgets to [copyWith] surfaces as a guarded `InternalError` rather than
 * silently serving an unscoped (ACL-bypassing) feed.
 */
internal class ActivityServiceImpl(
    private val activities: ActivityRepository,
    private val bookAccessPolicy: BookAccessPolicy,
    private val publicProfiles: PublicProfileRepository,
    private val principal: PrincipalProvider,
) : ActivityService {
    override suspend fun feed(
        before: Long?,
        limit: Int,
    ): AppResult<List<ActivityEvent>> {
        val caller = resolveCaller() ?: return noPrincipal()
        // accessibleBookIds returns null for ROOT/ADMIN — unconstrained, every book-bearing row visible.
        val accessible = bookAccessPolicy.accessibleBookIds(caller.userId, caller.role)
        val visible = collectVisible(before, limit, accessible)
        val identities = publicProfiles.identities(visible.map { it.userId }.toSet())
        return AppResult.Success(
            visible.mapNotNull { row ->
                val identity = identities[row.userId] ?: return@mapNotNull null
                ActivityEvent(
                    id = row.id,
                    userId = row.userId,
                    displayName = identity.displayName,
                    avatarType = identity.avatarType,
                    type = row.type,
                    createdAtMs = row.createdAt,
                    bookId = row.bookId,
                    isReread = row.isReread,
                    durationMs = row.durationMs,
                    milestoneValue = row.milestoneValue,
                    milestoneUnit = row.milestoneUnit,
                    shelfId = row.shelfId,
                    shelfName = row.shelfName,
                )
            },
        )
    }

    /**
     * Overfetches raw pages and keeps the first [limit] rows the caller may see. A row passes when
     * it is non-book ([ActivityRow.bookId] is null), the caller is unconstrained ([accessible] is
     * null = ROOT/ADMIN), or its book is in the [accessible] set. Re-pages by the trailing
     * `created_at` cursor until the page is full or the log is exhausted.
     */
    private suspend fun collectVisible(
        before: Long?,
        limit: Int,
        accessible: Set<String>?,
    ): List<ActivityRow> {
        val visible = ArrayList<ActivityRow>(limit)
        var cursor = before
        while (visible.size < limit) {
            val pageRows = activities.page(cursor, limit * OVERFETCH)
            if (pageRows.isEmpty()) break
            pageRows
                .asSequence()
                .filter { it.canBeSeenWith(accessible) }
                .take(limit - visible.size)
                .forEach { visible += it }
            cursor = pageRows.last().createdAt
            if (pageRows.size < limit * OVERFETCH) break // log exhausted
        }
        return visible
    }

    /**
     * True when this row may be shown to the caller: a non-book row always passes; a book-bearing
     * row passes only when the caller is unconstrained ([accessible] null = ROOT/ADMIN) or its book
     * is in the [accessible] set.
     */
    private fun ActivityRow.canBeSeenWith(accessible: Set<String>?): Boolean =
        bookId == null || accessible == null || bookId in accessible

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): ActivityServiceImpl =
        ActivityServiceImpl(
            activities = activities,
            bookAccessPolicy = bookAccessPolicy,
            publicProfiles = publicProfiles,
            principal = principal,
        )

    /** The resolved caller: their user id and contract role (the role [BookAccessPolicy] speaks). */
    private data class Caller(
        val userId: String,
        val role: UserRole,
    )

    private fun resolveCaller(): Caller? = principal.current()?.let { Caller(it.userId.value, it.role) }

    private fun noPrincipal(): AppResult.Failure = AppResult.Failure(SocialError.NotFound())

    private companion object {
        /** Multiplier on the requested page size when overfetching to absorb ACL-filtered rows. */
        const val OVERFETCH = 3
    }
}

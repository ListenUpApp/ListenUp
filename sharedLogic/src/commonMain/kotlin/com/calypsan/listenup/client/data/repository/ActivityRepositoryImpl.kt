package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.ActivityWithProfile
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookSummary
import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.presentation.profile.stableAvatarColorHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Activity-feed repository — a pure Room read seam over the cursored `activities` MirrorDomain.
 *
 * Writes arrive on the sync data channel (the domain's `MirrorApply` upserts entities); this
 * repository only READS. Each row is enriched at read time: identity (display name, avatar) comes
 * from a LEFT JOIN to the local `public_profiles` mirror (so a rename reflects — the Room Flow
 * re-emits when either table changes), and the book card is reconstructed per row from the local
 * book mirror ([BookDao.getBookSummary]). The avatar colour is derived locally via
 * [stableAvatarColorHex] so it matches the rest of the app.
 *
 * @property dao Room DAO for the local activity mirror (enriched reads).
 * @property bookDao Local book mirror, for the book card on book-bearing activities.
 */
internal class ActivityRepositoryImpl(
    private val dao: ActivityDao,
    private val bookDao: BookDao,
) : ActivityRepository {
    override fun observeRecent(limit: Int): Flow<List<Activity>> =
        dao.observeRecent(limit).map { rows -> rows.enrich() }

    override suspend fun getOlderThan(
        beforeMs: Long,
        limit: Int,
    ): List<Activity> = dao.getOlderThan(beforeMs, limit).enrich()

    override suspend fun getNewestTimestamp(): Long? = dao.getNewestTimestamp()

    override suspend fun count(): Int = dao.count()

    /**
     * Enrich each joined row into a domain [Activity], reconstructing the book card from the local
     * book mirror. A plain loop (not `List.map`) because the per-row book lookup is a suspend call.
     */
    private suspend fun List<ActivityWithProfile>.enrich(): List<Activity> =
        buildList {
            for (row in this@enrich) add(row.toDomain(bookDao))
        }
}

/** Reconstruct the book card from a local [BookSummary]; the blur hash stands in for the cover. */
private fun BookSummary.toActivityBook(): Activity.ActivityBook =
    Activity.ActivityBook(
        id = id,
        title = title,
        authorName = authorName,
        coverPath = coverBlurHash,
    )

/**
 * Map an enriched [ActivityWithProfile] row to a domain [Activity]. Identity comes from the joined
 * `public_profiles` row (falling back to empty/`auto` when the author's profile is not yet mirrored);
 * the avatar colour is derived locally; the book card is looked up from the local book mirror.
 */
private suspend fun ActivityWithProfile.toDomain(bookDao: BookDao): Activity {
    val summary = bookId?.let { bookDao.getBookSummary(it) }
    return Activity(
        id = id,
        type = type,
        userId = userId,
        occurredAtMs = occurredAt,
        user =
            Activity.ActivityUser(
                displayName = displayName.orEmpty(),
                avatarColor = stableAvatarColorHex(userId),
                avatarType = avatarType ?: "auto",
                avatarValue = avatarValue,
            ),
        book = summary?.toActivityBook(),
        isReread = isReread,
        durationMs = durationMs,
        milestoneValue = milestoneValue,
        milestoneUnit = milestoneUnit,
        shelfId = shelfId,
        shelfName = shelfName,
    )
}

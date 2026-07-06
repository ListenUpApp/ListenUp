package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.stableAvatarColorHex
import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.ActivityWithProfile
import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Activity-feed repository — a pure Room read seam over the cursored `activities` MirrorDomain.
 *
 * Writes arrive on the sync data channel (the domain's `MirrorApply` upserts entities); this
 * repository only READS. Every row is enriched entirely in the DAO query ([ActivityDao.observeRecent])
 * by joining the local `public_profiles` and `books` mirrors — so identity, the book card, and a
 * later rename of any of them all reflect reactively (the Room Flow re-emits on any joined table),
 * with no per-row N+1. The only read-time computation left here is the deterministic avatar colour.
 *
 * @property dao Room DAO for the local activity mirror (fully-enriched reads).
 */
internal class ActivityRepositoryImpl(
    private val dao: ActivityDao,
) : ActivityRepository {
    override fun observeRecent(limit: Int): Flow<List<Activity>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }
}

/**
 * Map a fully-enriched [ActivityWithProfile] row to a domain [Activity]. Identity comes from the
 * joined `public_profiles` row (empty/`auto` fallback when the author's profile is not yet mirrored);
 * the avatar colour is derived locally; the book card comes from the joined `books` row and is null
 * when the activity has no book or the book is inaccessible/tombstoned locally.
 */
private fun ActivityWithProfile.toDomain(): Activity =
    Activity(
        id = id,
        type = type,
        userId = userId,
        occurredAtMs = occurredAt,
        user =
            Activity.ActivityUser(
                displayName = displayName.orEmpty(),
                avatarColor = stableAvatarColorHex(userId),
                avatarType = avatarType ?: "auto",
                avatarValue = null,
            ),
        book =
            if (bookId != null && bookTitle != null) {
                Activity.ActivityBook(
                    id = bookId,
                    title = bookTitle,
                    authorName = bookAuthorName,
                    coverPath = bookCoverPath,
                )
            } else {
                null
            },
        isReread = isReread,
        durationMs = durationMs,
        milestoneValue = milestoneValue,
        milestoneUnit = milestoneUnit,
        shelfId = shelfId,
        shelfName = shelfName,
    )

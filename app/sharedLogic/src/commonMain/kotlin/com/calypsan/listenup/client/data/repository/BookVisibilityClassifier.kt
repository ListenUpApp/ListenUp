package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.AdminUserRosterEntity
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.CollectionShareEntity
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.CollectionRef
import com.calypsan.listenup.client.domain.model.HiddenFrom

/** Roster roles that see every book, so are never "hidden from" anything. Server enum names. */
private val SEES_EVERYTHING = setOf("ROOT", "ADMIN")

/** The only roster status that counts as a member; a pending user sees nothing at all yet. */
private const val ACTIVE = "ACTIVE"

/**
 * Classify one book's visibility from the rows an admin's device syncs.
 *
 * [isHeld] comes from the inbox's own held fragment (`HELD_BOOK_IDS_SQL`), never from [holding],
 * so this and the Admin Inbox cannot disagree. [holding] is the live collections the book is a live
 * member of; tombstones are filtered here as well as in the queries. Precedence, in order:
 *  1. held → [BookVisibility.Held], even alongside a normal collection (the overlap is transient:
 *     curating releases);
 *  2. no live collection → [BookVisibility.Stranded];
 *  3. no normal collection (All Books only) → [BookVisibility.Public];
 *  4. otherwise → [BookVisibility.Restricted], naming who cannot see it.
 *
 * Restricted is decided by "has a normal membership", never by "not in All Books": after a local
 * curation the All Books row only leaves with the server's echo.
 */
internal fun classifyBookVisibility(
    isHeld: Boolean,
    holding: List<CollectionEntity>,
    shares: List<CollectionShareEntity>,
    roster: List<AdminUserRosterEntity>,
): BookVisibility {
    val live = holding.filter { it.deletedAt == null }.distinctBy { it.id }
    val normal = live.filter { it.isNormal }
    return when {
        isHeld -> {
            BookVisibility.Held
        }

        live.isEmpty() -> {
            BookVisibility.Stranded
        }

        normal.isEmpty() -> {
            BookVisibility.Public
        }

        else -> {
            BookVisibility.Restricted(
                collections =
                    normal
                        .map { CollectionRef(id = it.id, name = it.name) }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
                hiddenFrom = hiddenFrom(normal, shares, roster),
            )
        }
    }
}

private val CollectionEntity.isNormal: Boolean get() = !isSystem && !isInbox

/** Members who neither own nor are shared into any of [normal]. */
private fun hiddenFrom(
    normal: List<CollectionEntity>,
    shares: List<CollectionShareEntity>,
    roster: List<AdminUserRosterEntity>,
): HiddenFrom {
    val normalIds = normal.mapTo(mutableSetOf()) { it.id }
    val withAccess =
        normal.mapTo(mutableSetOf()) { it.ownerId } +
            shares.filter { it.deletedAt == null && it.collectionId in normalIds }.map { it.sharedWithUserId }
    val members = roster.filter { it.deletedAt == null && it.status == ACTIVE && it.role !in SEES_EVERYTHING }
    val hidden = members.filterNot { it.id in withAccess }
    return when {
        hidden.isEmpty() -> {
            HiddenFrom.Nobody
        }

        hidden.size == members.size -> {
            HiddenFrom.Everyone
        }

        else -> {
            HiddenFrom.Members(
                hidden
                    .map { user ->
                        user.displayName.ifBlank { user.email }
                    }.sortedWith(String.CASE_INSENSITIVE_ORDER),
            )
        }
    }
}

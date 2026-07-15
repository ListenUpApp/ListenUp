package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.sync.BioEntryMode
import com.calypsan.listenup.api.sync.EntityKind

/**
 * Domain model for a Story World entity — character, location, or item — library-shared,
 * curated world data namespaced under a series (Story World Stage 2). Reuses the contract's
 * [EntityKind] directly — it is a wire concept with no client-only semantics, the
 * [com.calypsan.listenup.client.domain.model.CollectionShare] /
 * [com.calypsan.listenup.api.dto.SharePermission] precedent.
 *
 * [bioEntries] is populated on a single-entity read ([observeEntity][com.calypsan.listenup.client.domain.repository.EntityEditRepository.observeEntity])
 * but left empty on a list projection
 * ([observeEntitiesForSeries][com.calypsan.listenup.client.domain.repository.EntityEditRepository.observeEntitiesForSeries])
 * — the same list-vs-detail split [com.calypsan.listenup.client.domain.model.ReadingOrder] /
 * [com.calypsan.listenup.client.domain.model.ReadingOrderDetail] use, without a second type
 * since an entity's bio-entry set is small and bounded (unlike a reading order's book list).
 *
 * @property id Stable, client-minted identifier (random UUID, stable across create and edit).
 * @property kind The entity's taxonomy — character, location, or item.
 * @property name The entity's first-introduced (pre-reveal) name.
 * @property homeSeriesId The series this entity is namespaced under — a namespacing key only.
 * @property imageRef Reserved reference to an entity portrait/image; null until image support ships.
 * @property bioEntries The entity's spoiler-anchored biography entries, in fold order.
 */
data class Entity(
    val id: String,
    val kind: EntityKind,
    val name: String,
    val homeSeriesId: String,
    val imageRef: String? = null,
    val bioEntries: List<BioEntry> = emptyList(),
)

/**
 * One spoiler-anchored biography entry on an [Entity] (Story World Stage 2). Reuses the
 * contract's [BioEntryMode] directly — see [Entity]'s KDoc for the wire-enum-reuse precedent.
 *
 * @property id Stable identifier for this bio entry.
 * @property bookId The spoiler anchor's book; null means this entry is always visible.
 * @property positionMs The spoiler anchor's position within [bookId], in milliseconds.
 * @property mode How this entry combines with earlier-anchored entries when folded.
 * @property text The entry's body text.
 * @property sortKey Ordering among entries that share the same anchor — lower values fold first.
 */
data class BioEntry(
    val id: String,
    val bookId: String? = null,
    val positionMs: Long? = null,
    val mode: BioEntryMode,
    val text: String,
    val sortKey: Int,
)

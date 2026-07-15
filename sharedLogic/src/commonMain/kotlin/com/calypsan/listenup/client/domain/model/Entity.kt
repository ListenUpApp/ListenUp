package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.sync.EntityKind

/**
 * Domain model for a Story World entity — character, location, or item — library-shared,
 * curated world data namespaced under either a series or a standalone book (Story World
 * Stage 2). Reuses the contract's [EntityKind] directly — it is a wire concept with no
 * client-only semantics, the [com.calypsan.listenup.client.domain.model.CollectionShare] /
 * [com.calypsan.listenup.api.dto.SharePermission] precedent.
 *
 * Entities are dual-homed: exactly one of [homeSeriesId] / [homeBookId] is non-null — never
 * both, never neither. See
 * [com.calypsan.listenup.api.sync.EntitySyncPayload] for the full dual-home rule.
 *
 * @property id Stable, client-minted identifier (random UUID, stable across create and edit).
 * @property kind The entity's taxonomy — character, location, or item.
 * @property name The entity's first-introduced (pre-reveal) name.
 * @property homeSeriesId The series this entity is namespaced under — a namespacing key only.
 *   Exactly one of [homeSeriesId] / [homeBookId] is non-null.
 * @property homeBookId The standalone book this entity is namespaced under — a namespacing key
 *   only. Exactly one of [homeSeriesId] / [homeBookId] is non-null.
 * @property imageRef Reserved reference to an entity portrait/image; null until image support ships.
 */
data class Entity(
    val id: String,
    val kind: EntityKind,
    val name: String,
    val homeSeriesId: String? = null,
    val homeBookId: String? = null,
    val imageRef: String? = null,
)

package com.calypsan.listenup.api.dto.world

import com.calypsan.listenup.api.sync.WorldEventType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Full-field snapshot for a Story World event create-or-update — the outbox payload for the
 * `world_events` write channel.
 *
 * Every field is present: the editing client always holds the current row (or is minting a new
 * one), so the queued op is a last-write-wins snapshot, mirroring
 * [com.calypsan.listenup.api.dto.entity.EntityUpsert]. Excludes fields that are server-managed
 * or set elsewhere: [com.calypsan.listenup.api.sync.WorldEventSyncPayload.revision],
 * `.mentionIds` (server-recomputed from [text] plus [subjectEntityId] / [objectEntityId] on every
 * write), and `.source` (fixed to `MANUAL` for every caller-authored write; import pipelines set
 * `IMPORTED` on a separate path once they exist).
 *
 * @property id Stable, client-minted identifier — random UUID, stable across a create (new id)
 *   and every subsequent edit (same id).
 * @property homeSeriesId The series this event is namespaced under. Exactly one of
 *   [homeSeriesId] / [homeBookId] is non-null; see
 *   [com.calypsan.listenup.api.sync.WorldEventSyncPayload] for the dual-home rule.
 * @property homeBookId The standalone book this event is namespaced under. Exactly one of
 *   [homeSeriesId] / [homeBookId] is non-null.
 * @property bookId The book this event is anchored to, or null when the event carries no book
 *   anchor. When non-null, [positionMs] must also be non-null.
 * @property positionMs Millisecond offset within [bookId]'s total duration. Null exactly when
 *   [bookId] is null.
 * @property type The event's typed vocabulary slot; see
 *   [com.calypsan.listenup.api.sync.WorldEventType] for which are reduced today.
 * @property text Free text describing the event; may carry
 *   [com.calypsan.listenup.api.core.MentionTokens]-encoded mentions inline.
 * @property subjectEntityId The entity this event is principally about, if any.
 * @property objectEntityId A second entity this event relates [subjectEntityId] to, if any.
 */
@Serializable
@SerialName("WorldEventUpsert")
data class WorldEventUpsert(
    @SerialName("id") val id: String,
    @SerialName("homeSeriesId") val homeSeriesId: String? = null,
    @SerialName("homeBookId") val homeBookId: String? = null,
    @SerialName("bookId") val bookId: String? = null,
    @SerialName("positionMs") val positionMs: Long? = null,
    @SerialName("type") val type: WorldEventType,
    @SerialName("text") val text: String,
    @SerialName("subjectEntityId") val subjectEntityId: String? = null,
    @SerialName("objectEntityId") val objectEntityId: String? = null,
)

/**
 * The offline-first outbox payload for a Story World event lifecycle edit, riding the
 * `world_events` outbox channel keyed by the event's id.
 *
 * [Upsert] carries the full-field [WorldEventUpsert] snapshot: unlike PATCH-shaped mutations on
 * other domains, a single create-or-update RPC backs every event write — a brand-new
 * (client-minted id) event and an edit to an existing one both replay through the same variant.
 * [Delete] soft-deletes. Both variants are last-write-wins / idempotent, so the channel is safe
 * to re-fire. Mirrors [com.calypsan.listenup.api.dto.entity.EntityMutation].
 */
@Serializable
sealed interface WorldEventOp {
    /**
     * The full-field event snapshot.
     *
     * @property upsert the event's complete current state.
     */
    @Serializable
    @SerialName("WorldEventOp.Upsert")
    data class Upsert(
        @SerialName("upsert") val upsert: WorldEventUpsert,
    ) : WorldEventOp

    /**
     * Soft-delete the event identified by [id].
     *
     * @property id the event's stable id.
     */
    @Serializable
    @SerialName("WorldEventOp.Delete")
    data class Delete(
        @SerialName("id") val id: String,
    ) : WorldEventOp
}

/**
 * A batch of [WorldEventOp]s riding a single outbox row.
 *
 * The batch is the atomic unit of the `world_events` write channel: the server applies every op
 * in [ops] as one transaction. A single bad op fails the whole batch server-side rather than
 * applying a partial batch — the client never has to reconcile a torn write.
 *
 * @property ops the ordered operations to apply atomically.
 */
@Serializable
@SerialName("EventsBatch")
data class EventsBatch(
    @SerialName("ops") val ops: List<WorldEventOp>,
)

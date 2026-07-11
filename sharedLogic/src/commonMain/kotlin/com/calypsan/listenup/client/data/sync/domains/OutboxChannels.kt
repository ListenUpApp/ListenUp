package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.sync.SyncDomains

/**
 * Every declared client→server write channel — the complete outbox rulebook.
 *
 * Mirrored domains take their name from their [SyncDomains] key so the contract,
 * the descriptor's [WriteTier.Outbox], and the queue all share one identity.
 * `profile` and `preferences` are client-only channels: RPC edit surfaces with no
 * mirrored descriptor (profile's inbound echo arrives via the `public_profiles`
 * mirror; preferences' via the `PreferencesChanged` refreshed domain).
 */
internal object OutboxChannels {
    // Update = set-fields, last-write-wins → inherently idempotent (a repeated PATCH re-applies the same snapshot).
    val Books = OutboxChannel(SyncDomains.BOOKS.name, BookUpdate.serializer(), setOf(OpKind.Update), idempotent = true)
    val Series = OutboxChannel(SyncDomains.SERIES.name, SeriesUpdate.serializer(), setOf(OpKind.Update), idempotent = true)
    val Contributors =
        OutboxChannel(
            SyncDomains.CONTRIBUTORS.name,
            ContributorUpdate.serializer(),
            setOf(OpKind.Update),
            idempotent = true,
        )

    // PlaybackService.recordPosition is documented "Idempotent and lastPlayedAt-wins server-side."
    val Positions =
        OutboxChannel(
            SyncDomains.PLAYBACK_POSITIONS.name,
            RecordPositionRequest.serializer(),
            setOf(OpKind.Upsert),
            idempotent = true,
        )

    // PlaybackService.recordListeningEvent is documented "Idempotent (re-recording the same id ...)."
    val ListeningEvents =
        OutboxChannel(
            SyncDomains.LISTENING_EVENTS.name,
            RecordListeningEventRequest.serializer(),
            setOf(OpKind.Upsert),
            idempotent = true,
        )
    val Profile =
        OutboxChannel("profile", UpdateProfileRequest.serializer(), setOf(OpKind.Update), idempotent = true)
    val Preferences =
        OutboxChannel("preferences", UpdateUserPreferencesRequest.serializer(), setOf(OpKind.Update), idempotent = true)

    /** The complete, ordered channel list — the set the sender map must bind exactly. */
    val all: List<OutboxChannel<*>> =
        listOf(Books, Series, Contributors, Positions, ListeningEvents, Profile, Preferences)

    private val byName: Map<String, OutboxChannel<*>> = all.associateBy { it.name }

    /**
     * Whether re-firing an op on [domainName] after a provably-sent-but-unconfirmed drop
     * (TransportError.OutcomeUnknown) is safe. Unknown domain → false: quarantine conservatively
     * rather than risk double-applying a mutation whose idempotency was never declared.
     */
    fun isIdempotent(domainName: String): Boolean = byName[domainName]?.idempotent ?: false
}

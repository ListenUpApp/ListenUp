package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderBookWrite
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderUpdate
import com.calypsan.listenup.api.dto.readingorder.SetActiveReadingOrderRequest
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
    val Books = OutboxChannel(SyncDomains.BOOKS.name, BookUpdate.serializer(), setOf(OpKind.Update))
    val Series = OutboxChannel(SyncDomains.SERIES.name, SeriesUpdate.serializer(), setOf(OpKind.Update))
    val Contributors =
        OutboxChannel(SyncDomains.CONTRIBUTORS.name, ContributorUpdate.serializer(), setOf(OpKind.Update))
    val Positions =
        OutboxChannel(SyncDomains.PLAYBACK_POSITIONS.name, RecordPositionRequest.serializer(), setOf(OpKind.Upsert))
    val ListeningEvents =
        OutboxChannel(
            SyncDomains.LISTENING_EVENTS.name,
            RecordListeningEventRequest.serializer(),
            setOf(OpKind.Upsert),
        )
    val ReadingOrders =
        OutboxChannel(SyncDomains.READING_ORDERS.name, ReadingOrderUpdate.serializer(), setOf(OpKind.Update))
    val ReadingOrderBooks =
        OutboxChannel(
            SyncDomains.READING_ORDER_BOOKS.name,
            ReadingOrderBookWrite.serializer(),
            setOf(OpKind.Create, OpKind.Delete, OpKind.Update),
        )
    val ReadingOrderFollows =
        OutboxChannel(
            SyncDomains.READING_ORDER_FOLLOWS.name,
            SetActiveReadingOrderRequest.serializer(),
            setOf(OpKind.Upsert),
        )
    val Profile = OutboxChannel("profile", UpdateProfileRequest.serializer(), setOf(OpKind.Update))
    val Preferences =
        OutboxChannel("preferences", UpdateUserPreferencesRequest.serializer(), setOf(OpKind.Update))

    /** The complete, ordered channel list — the set the sender map must bind exactly. */
    val all: List<OutboxChannel<*>> =
        listOf(
            Books,
            Series,
            Contributors,
            Positions,
            ListeningEvents,
            ReadingOrders,
            ReadingOrderBooks,
            ReadingOrderFollows,
            Profile,
            Preferences,
        )
}

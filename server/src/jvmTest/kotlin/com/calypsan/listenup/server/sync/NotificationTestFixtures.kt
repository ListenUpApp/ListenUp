package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.sync.NotificationSyncPayload
import com.calypsan.listenup.server.testing.SqlTestDatabases
import kotlin.time.Clock

/*
 * Shared fixtures for the notifications-domain suites ([NotificationUserScopingTest],
 * [NotificationRepositoryTest]) — one repository builder and one payload builder, so every
 * test seeds rows the same way.
 */

/** A [NotificationRepository] over the test database with throwaway bus/registry fixtures. */
internal fun SqlTestDatabases.notificationFixture(
    bus: ChangeBus = ChangeBus(),
    clock: Clock = Clock.System,
) = NotificationRepository(db = sql, bus = bus, registry = SyncRegistry(), clock = clock)

/** A wire payload for [id] carrying a real encoded [NotificationEvent]; substrate authors the rest. */
internal fun notificationPayload(
    id: String,
    event: NotificationEvent =
        NotificationEvent.CampfireInvite(
            campfireId = "camp-1",
            bookId = "book-1",
            inviterUserId = "inviter-1",
        ),
): NotificationSyncPayload =
    NotificationSyncPayload(
        id = id,
        type = event.wireType,
        body = contractJson.encodeToString(NotificationEvent.serializer(), event),
        createdAt = 0L,
        updatedAt = 0L,
        readAt = null,
        revision = 0L,
        deletedAt = null,
    )

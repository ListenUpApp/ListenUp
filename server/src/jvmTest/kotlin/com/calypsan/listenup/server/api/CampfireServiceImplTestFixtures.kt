@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.campfire.CampfireInviteNotifier
import com.calypsan.listenup.server.campfire.CampfireRegistry
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

/**
 * Shared fixtures for [CampfireServiceImplAccessTest] and [CampfireServiceImplActivityTest] — split
 * from a single `CampfireServiceImplTest` to keep each file under the LargeClass threshold. Internal:
 * these are test-construction helpers, not part of the service's public surface.
 */
internal val campfireTestT0: Instant = Instant.fromEpochMilliseconds(1_730_000_000_000L)

internal val campfireEveryoneSettings =
    CampfireSettings(name = "Test Campfire", controlMode = CampfireControlMode.EVERYONE, inviteOnly = false)
internal val campfireHostOnlySettings =
    CampfireSettings(name = "Test Campfire", controlMode = CampfireControlMode.HOST_ONLY, inviteOnly = false)

internal fun principalFor(userId: String): PrincipalProvider =
    PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), UserRole.MEMBER) }

internal fun makeService(
    sql: ListenUpDatabase,
    driver: SqlDriver,
    registry: CampfireRegistry,
    principal: PrincipalProvider,
    clock: kotlin.time.Clock = FixedClock(campfireTestT0),
    bus: ChangeBus = ChangeBus(),
    pushNotifier: PushNotifier = RecordingPushNotifier(),
    activityRecorder: ActivityRecorder =
        ActivityRecorder(
            syncRepo = ActivitySyncRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver),
        ),
): CampfireServiceImpl {
    val syncRegistry = SyncRegistry()
    return CampfireServiceImpl(
        registry = registry,
        bookAccessPolicy = BookAccessPolicy(sql, driver),
        playbackPositions = PlaybackPositionRepository(db = sql, bus = bus, registry = syncRegistry),
        publicProfiles = PublicProfileRepository(db = sql, bus = bus, registry = syncRegistry),
        db = sql,
        bus = bus,
        userRoleLookup = UserRoleLookup(db = sql),
        inviteNotifier = CampfireInviteNotifier(pushNotifier = pushNotifier),
        activityRecorder = activityRecorder,
        clock = clock,
        principal = principal,
    )
}

internal fun <T> AppResult<T>.value(): T {
    this.shouldBeInstanceOf<AppResult.Success<T>>()
    return data
}

/** Makes [bookId] visible to [viewer] via the ALL_BOOKS pure-union path (see [SocialServiceTest]). */
internal suspend fun makeBookAccessible(
    sql: ListenUpDatabase,
    driver: SqlDriver,
    bookId: String,
    viewer: String,
    allBooksId: String = "all-books",
) {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val collectionRepo = CollectionRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
    val collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
    val grantRepo = CollectionGrantRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
    collectionRepo.upsert(
        CollectionSyncPayload(
            id = allBooksId,
            libraryId = "test-library",
            ownerId = "system",
            name = "All Books",
            isInbox = false,
            revision = 0L,
            updatedAt = 0L,
        ),
    )
    collectionBookRepo.upsert(
        CollectionBookSyncPayload(collectionId = allBooksId, bookId = bookId, createdAt = 0L, revision = 0L),
    )
    grantRepo.upsert(
        CollectionShareSyncPayload(
            id = "grant-$viewer-$bookId",
            collectionId = allBooksId,
            sharedWithUserId = viewer,
            sharedByUserId = "system",
            permission = SharePermission.Read,
            revision = 0L,
            updatedAt = 0L,
        ),
    )
}

/** Directly inserts an unfinished playback position row for [userId]/[bookId]. */
internal fun ListenUpDatabase.seedPosition(
    userId: String,
    bookId: String,
    positionMs: Long,
) {
    playbackPositionsQueries.insert(
        id = "$userId-$bookId",
        user_id = userId,
        book_id = bookId,
        position_ms = positionMs,
        last_played_at = 1L,
        finished = 0L,
        playback_speed = 1.0,
        current_chapter_id = null,
        revision = 0L,
        created_at = 1L,
        updated_at = 1L,
        deleted_at = null,
        client_op_id = null,
    )
}

/** Directly inserts a `book_chapters` row. */
internal fun ListenUpDatabase.seedChapter(
    bookId: String,
    ordinal: Long,
    startTimeMs: Long,
) {
    bookChaptersQueries.insert(
        book_id = bookId,
        ordinal = ordinal,
        id = "$bookId-ch-$ordinal",
        title = "Chapter $ordinal",
        duration = 0L,
        start_time = startTimeMs,
        part_title = null,
        book_title = null,
    )
}

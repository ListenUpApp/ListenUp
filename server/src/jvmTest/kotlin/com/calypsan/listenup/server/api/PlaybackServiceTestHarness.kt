package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.api.dto.CodecCapability
import com.calypsan.listenup.server.transcode.TranscodePolicy
import com.calypsan.listenup.server.transcode.TranscodeSettings
import com.calypsan.listenup.server.transcode.TranscoderAvailability
import com.calypsan.listenup.server.transcode.TranscoderStatus
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.audio.CoverUrlSigner
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.services.StatsRecorder
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Shared fixtures for the `PlaybackServiceImpl` specs.
 *
 * Extracted from `PlaybackServiceImplTest` when the transcoding cases arrived and pushed that spec
 * past detekt's `LargeClass` threshold: the decision table for capabilities is its own subject, and
 * duplicating a hundred lines of repository wiring to give it a home would have been worse than
 * either splitting or leaving one oversized file.
 */

internal data class TestDeps(
    val bookRepo: BookRepository,
    val positionRepo: PlaybackPositionRepository,
    val signer: AudioUrlSigner,
    val coverSigner: CoverUrlSigner,
    val eventRepo: ListeningEventRepository,
    val statsRepo: UserStatsRepository,
    val accessPolicy: BookAccessPolicy,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val grantRepo: CollectionGrantRepository,
)

internal fun buildDeps(
    sql: ListenUpDatabase,
    driver: SqlDriver,
): TestDeps {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val bookRepo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(sql, bus, registry),
            seriesRepository = SeriesRepository(sql, bus, registry),
            genreRepository = GenreRepository(sql, bus, registry),
        )
    val positionRepo = PlaybackPositionRepository(db = sql, bus = bus, registry = SyncRegistry())
    val signer = AudioUrlSigner(AudioUrlSigner.deriveSigningKey("x".repeat(32)))
    val coverSigner = CoverUrlSigner(CoverUrlSigner.deriveSigningKey("x".repeat(32)))
    val statsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
    val statsRecorder = buildStatsRecorderForTest(sql, driver, statsRepo)
    val eventRepo =
        ListeningEventRepository(
            db = sql,
            bus = ChangeBus(),
            registry = SyncRegistry(),
            statsRecorder = statsRecorder,
        )
    return TestDeps(
        bookRepo = bookRepo,
        positionRepo = positionRepo,
        signer = signer,
        coverSigner = coverSigner,
        eventRepo = eventRepo,
        statsRepo = statsRepo,
        accessPolicy = BookAccessPolicy(sql, driver),
        collectionRepo =
            CollectionRepository(
                db = sql,
                bus = bus,
                registry = registry,
                driver = driver,
            ),
        collectionBookRepo =
            CollectionBookRepository(
                db = sql,
                bus = bus,
                registry = registry,
                driver = driver,
            ),
        grantRepo =
            CollectionGrantRepository(
                db = sql,
                bus = bus,
                registry = registry,
                driver = driver,
            ),
    )
}

internal fun principal(
    userId: String = "u1",
    role: UserRole = UserRole.MEMBER,
): PrincipalProvider =
    PrincipalProvider {
        UserPrincipal(
            userId = UserId(userId),
            sessionId = SessionId("session-$userId"),
            role = role,
        )
    }

internal fun TestDeps.service(
    sql: ListenUpDatabase,
    userId: String = "u1",
    role: UserRole = UserRole.MEMBER,
    availability: TranscoderAvailability = TranscoderAvailability(),
): PlaybackServiceImpl =
    PlaybackServiceImpl(
        bookRepository = bookRepo,
        audioFileLocator = AudioFileLocator(sql),
        audioUrlSigner = signer,
        coverUrlSigner = coverSigner,
        playbackPositionRepository = positionRepo,
        listeningEventRepository = eventRepo,
        userStatsRepository = statsRepo,
        accessPolicy = accessPolicy,
        principal = principal(userId, role),
        sql = sql,
        transcodePolicy = TranscodePolicy(),
        transcodeSettings = TranscodeSettings(),
        transcoderAvailability = availability,
    )

/**
 * Makes [bookId] visible to each (already-seeded) member in [userIds] the pure-union way:
 * drops the book into the per-library `ALL_BOOKS` substrate and grants each a live read
 * grant on it — exactly how production exposes a public book. The users must already exist
 * (`seedTestUser`): the grant's `principal_id` is a FK into `users`.
 */
internal suspend fun TestDeps.makeReachable(
    bookId: String,
    vararg userIds: String,
) {
    collectionRepo.upsert(playbackCollection("all-books", owner = "system"))
    collectionBookRepo.upsert(playbackMembership("all-books", bookId))
    for (uid in userIds) {
        grantRepo.upsert(playbackShare("grant-$bookId-$uid", "all-books", uid))
    }
}

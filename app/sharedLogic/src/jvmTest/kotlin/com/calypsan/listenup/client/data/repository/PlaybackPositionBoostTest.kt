package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.domains.PlaybackPositionMirrorApply
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Pins the per-book volume-boost persistence semantics, mirroring the speed path:
 * [PlaybackUpdate.VolumeBoost]/[PlaybackUpdate.BoostReset] persist `volumeBoostDb` +
 * `hasCustomBoost`, [PlaybackUpdate.MeasuredGain] persists ONLY `measuredGainDb`,
 * periodic position writes never clobber the boost columns, the enqueued
 * [RecordPositionRequest] carries the boost fields, and inbound sync preserves the
 * client-local `hasCustomBoost` flag while taking boost values from the payload.
 */
class PlaybackPositionBoostTest :
    FunSpec({

        fun playedEntity(bookId: BookId) =
            PlaybackPositionEntity(
                bookId = bookId,
                positionMs = 90_000L,
                playbackSpeed = 1.25f,
                hasCustomSpeed = true,
                updatedAt = 1_000L,
                syncedAt = 1_000L,
                lastPlayedAt = 1_000L,
                isFinished = false,
                finishedAt = null,
                startedAt = 500L,
            )

        fun repoAgainst(db: ListenUpDatabase): PlaybackPositionRepositoryImpl =
            PlaybackPositionRepositoryImpl(
                dao = db.playbackPositionDao(),
                transactionRunner = RoomTransactionRunner(db),
                pendingQueue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = { AppResult.Success(Unit) },
                    ),
                authSession = FakeAuthSession(userId = "u1"),
            )

        suspend fun singleQueuedRequest(db: ListenUpDatabase): RecordPositionRequest {
            val ops = db.pendingOperationV2Dao().observePending().first()
            ops shouldHaveSize 1
            return contractJson.decodeFromString(RecordPositionRequest.serializer(), ops.single().payload)
        }

        test("VolumeBoost persists boostDb and hasCustomBoost without moving the position") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId))

                    repo
                        .savePlaybackState(
                            bookId,
                            PlaybackUpdate.VolumeBoost(boostDb = 6f, custom = true, positionMs = 90_000L),
                        ).shouldBeInstanceOf<AppResult.Success<*>>()

                    val row = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                    row.volumeBoostDb shouldBe 6f
                    row.hasCustomBoost shouldBe true
                    row.positionMs shouldBe 90_000L
                } finally {
                    db.close()
                }
            }
        }

        test("BoostReset clears hasCustomBoost and persists the default boost") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(
                        playedEntity(bookId).copy(volumeBoostDb = 6f, hasCustomBoost = true),
                    )

                    repo
                        .savePlaybackState(
                            bookId,
                            PlaybackUpdate.BoostReset(defaultBoostDb = 2f, positionMs = 90_000L),
                        ).shouldBeInstanceOf<AppResult.Success<*>>()

                    val row = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                    row.volumeBoostDb shouldBe 2f
                    row.hasCustomBoost shouldBe false
                } finally {
                    db.close()
                }
            }
        }

        test("MeasuredGain sets ONLY measuredGainDb — boost value and hasCustomBoost untouched") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(
                        playedEntity(bookId).copy(volumeBoostDb = 6f, hasCustomBoost = true),
                    )

                    repo
                        .savePlaybackState(
                            bookId,
                            PlaybackUpdate.MeasuredGain(gainDb = -2.5f, positionMs = 90_000L),
                        ).shouldBeInstanceOf<AppResult.Success<*>>()

                    val row = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                    row.measuredGainDb shouldBe -2.5f
                    row.volumeBoostDb shouldBe 6f
                    row.hasCustomBoost shouldBe true
                } finally {
                    db.close()
                }
            }
        }

        test("a periodic position-only update after a boost change does not clobber the boost columns") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId))

                    repo
                        .savePlaybackState(
                            bookId,
                            PlaybackUpdate.VolumeBoost(boostDb = 6f, custom = true, positionMs = 90_000L),
                        ).shouldBeInstanceOf<AppResult.Success<*>>()
                    repo
                        .savePlaybackState(
                            bookId,
                            PlaybackUpdate.PeriodicUpdate(positionMs = 95_000L, speed = 1.25f),
                        ).shouldBeInstanceOf<AppResult.Success<*>>()

                    val row = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                    row.positionMs shouldBe 95_000L
                    row.volumeBoostDb shouldBe 6f
                    row.hasCustomBoost shouldBe true
                } finally {
                    db.close()
                }
            }
        }

        test("the enqueued RecordPositionRequest for a boost change carries the boost fields") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId).copy(measuredGainDb = -1.5f))

                    repo
                        .savePlaybackState(
                            bookId,
                            PlaybackUpdate.VolumeBoost(boostDb = 6f, custom = true, positionMs = 90_000L),
                        ).shouldBeInstanceOf<AppResult.Success<*>>()

                    val request = singleQueuedRequest(db)
                    request.volumeBoostDb shouldBe 6f
                    request.measuredGainDb shouldBe -1.5f
                } finally {
                    db.close()
                }
            }
        }

        test("a position write's enqueued request carries the row's current boost fields") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(
                        playedEntity(bookId).copy(volumeBoostDb = 4f, measuredGainDb = -1.5f),
                    )

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.Position(positionMs = 95_000L, speed = 1.25f))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val request = singleQueuedRequest(db)
                    request.volumeBoostDb shouldBe 4f
                    request.measuredGainDb shouldBe -1.5f
                } finally {
                    db.close()
                }
            }
        }

        test("inbound sync takes boost values from the payload but preserves local hasCustomBoost") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(
                        playedEntity(bookId).copy(volumeBoostDb = 6f, hasCustomBoost = true),
                    )

                    PlaybackPositionMirrorApply(db).upsert(
                        PlaybackPositionSyncPayload(
                            id = "pos-1",
                            bookId = bookId.value,
                            positionMs = 42_000L,
                            lastPlayedAt = 9_000L,
                            finished = false,
                            playbackSpeed = 1.25f,
                            currentChapterId = null,
                            volumeBoostDb = 3f,
                            measuredGainDb = -2f,
                            revision = 2L,
                            updatedAt = 9_000L,
                            createdAt = 50L,
                            deletedAt = null,
                        ),
                    )

                    val row = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                    row.volumeBoostDb shouldBe 3f
                    row.measuredGainDb shouldBe -2f
                    row.hasCustomBoost shouldBe true
                } finally {
                    db.close()
                }
            }
        }
    })

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.sync.TargetedMatch
import com.calypsan.listenup.server.testing.rows
import com.calypsan.listenup.server.testing.shouldFailWith
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class SyncCatchUpPullTest :
    FunSpec({

        test("pullDomain(tags, since=0) returns all rows") {
            withTestApplication {
                tagRepo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                tagRepo.upsert(Tag("b", "beta", "beta", 0, 0))

                val page = syncService().pullDomain("tags", since = 0, limit = 100).shouldSucceed()
                val decoded = page.rows(Tag.serializer())
                decoded shouldHaveSize 2
                decoded.map { it.id } shouldBe listOf("a", "b")
            }
        }

        test("pullDomain on an unknown domain fails with SyncError.UnknownDomain") {
            withTestApplication {
                syncService()
                    .pullDomain("nonexistent", since = 0, limit = 100)
                    .shouldFailWith<SyncError.UnknownDomain>()
            }
        }

        test("pullByIds(tags, ID) over the 100-id cap fails with SyncError.TooManyIds") {
            withTestApplication {
                // The cap is enforced before the repo read, so it fires on any registered domain.
                val ids = (1..101).map { "id-$it" }
                syncService()
                    .pullByIds("tags", TargetedMatch.ID, ids)
                    .shouldFailWith<SyncError.TooManyIds>()
            }
        }

        test("pullByIds(tags, COLLECTION_ID) over the 100-id cap fails with SyncError.TooManyIds") {
            withTestApplication {
                val ids = (1..101).map { "col-$it" }
                syncService()
                    .pullByIds("tags", TargetedMatch.COLLECTION_ID, ids)
                    .shouldFailWith<SyncError.TooManyIds>()
            }
        }

        test(
            "pullByIds(tags, BOOK_ID) fails with SyncError.UnsupportedMatch " +
                "(allowlist keeps matchColumn sound)",
        ) {
            withTestApplication {
                // `tags` has no `book_id` column, so honoring the fetch would be a SQL error — the
                // per-domain allowlist rejects it before the repo read.
                syncService()
                    .pullByIds("tags", TargetedMatch.BOOK_ID, listOf("b1", "b2"))
                    .shouldFailWith<SyncError.UnsupportedMatch>()
            }
        }

        test("pullByIds(activities, BOOK_ID) over the 100-id cap fails with SyncError.TooManyIds") {
            withTestApplication(playbackEvents = true) {
                // activities is on the allowlist, so this reaches the id-cap guard.
                val ids = (1..101).map { "book-$it" }
                syncService()
                    .pullByIds("activities", TargetedMatch.BOOK_ID, ids)
                    .shouldFailWith<SyncError.TooManyIds>()
            }
        }

        test(
            "pullByIds(tags, ID) on a GLOBAL ungated domain returns the requested row (DRIFT-1 heal path)",
        ) {
            withTestApplication {
                tagRepo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                tagRepo.upsert(Tag("b", "beta", "beta", 0, 0))

                // `tags` is a GLOBAL ungated domain: every row is visible to every authenticated
                // caller, so a by-id fetch serves the rows directly — this is the read the client's
                // DRIFT-1 dead-letter heal uses to re-fetch current server truth for a curation entity.
                // Only the requested id comes back.
                val page = syncService().pullByIds("tags", TargetedMatch.ID, listOf("a")).shouldSucceed()
                page.rows(Tag.serializer()).map { it.id } shouldBe listOf("a")
            }
        }

        test(
            "pullByIds(playback_positions, ID) on a userScoped domain returns an empty page " +
                "even for an existing row (no cross-user leak)",
        ) {
            // A userScoped domain has no access-filter driver, so an unfiltered by-id read would leak
            // another user's rows. It must therefore answer an EMPTY page for a targeted fetch — even
            // when the row genuinely exists — rather than serving it (its convergence rides
            // `pullDomain`'s `since` cursor). Also the original invariant: a valid authenticated pull
            // never fails unexpectedly.
            withTestApplication(playbackPositions = true) {
                val seeded =
                    playbackPositionRepo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 1_000L,
                        lastPlayedAt = 0L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                val positionId = (seeded as AppResult.Success).data.id

                val page =
                    syncService()
                        .pullByIds("playback_positions", TargetedMatch.ID, listOf(positionId))
                        .shouldSucceed()
                page.rows(PlaybackPositionSyncPayload.serializer()).shouldBeEmpty()
            }
        }
    })

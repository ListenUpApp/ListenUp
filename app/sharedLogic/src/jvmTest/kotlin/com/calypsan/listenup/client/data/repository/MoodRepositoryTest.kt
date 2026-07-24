package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.Mood as WireMood
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.test.fake.noopOfflineEditor
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.domains.bookMoodsDomain
import com.calypsan.listenup.client.data.sync.domains.moodsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [MoodRepositoryImpl] observation, exercising the full
 * payload → Room entity → domain mapping through a real in-memory SQLite database.
 *
 * Mutations are RPC-backed and covered by the contract surface; here we verify the
 * offline-first Room read path (the only path the UI consumes).
 */
class MoodRepositoryTest :
    FunSpec({

        test("observeAllMoods maps Room rows to domain Mood, name-sorted") {
            withRepo { repo, db ->
                seedMood(db, "m1", "Tense", "tense")
                seedMood(db, "m2", "Feel-Good", "feel-good")

                repo.observeAllMoods().test {
                    val moods = awaitItem()
                    // ORDER BY name ASC → "Feel-Good" before "Tense".
                    moods.map { it.name }.shouldContainExactly("Feel-Good", "Tense")
                    moods.map { it.slug }.shouldContainExactly("feel-good", "tense")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeMoodsForBook returns only moods applied via a live junction row") {
            withRepo { repo, db ->
                seedMood(db, "m1", "Feel-Good", "feel-good")
                seedMood(db, "m2", "Scary", "scary")
                seedBookMood(db, "b1", "m1")

                repo.observeMoodsForBook("b1").test {
                    awaitItem().map { it.name }.shouldContainExactly("Feel-Good")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeMoodsForBook excludes tombstoned junction rows") {
            withRepo { repo, db ->
                seedMood(db, "m1", "Feel-Good", "feel-good")
                seedBookMood(db, "b1", "m1")
                db.bookMoodDao().tombstone("b1", "m1", deletedAt = 500L)

                repo.observeMoodsForBook("b1").test {
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun withRepo(block: suspend (MoodRepositoryImpl, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            val repo =
                MoodRepositoryImpl(
                    channel = RpcChannel.forTest(mock<MoodService>()),
                    moodDao = db.moodDao(),
                    bookMoodDao = db.bookMoodDao(),
                    offlineEditor = noopOfflineEditor(),
                )
            block(repo, db)
        } finally {
            db.close()
        }
    }

private suspend fun seedMood(
    db: ListenUpDatabase,
    id: String,
    name: String,
    slug: String,
) {
    moodsDomain(db)
        .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
        .onCatchUpItem(
            WireMood(id = id, name = name, slug = slug, revision = 1L, updatedAt = 100L, deletedAt = null),
            isTombstone = false,
        )
}

private suspend fun seedBookMood(
    db: ListenUpDatabase,
    bookId: String,
    moodId: String,
) {
    bookMoodsDomain(db)
        .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
        .onCatchUpItem(
            BookMoodSyncPayload(
                id = "$bookId:$moodId",
                bookId = bookId,
                moodId = moodId,
                createdAt = 100L,
                revision = 1L,
                deletedAt = null,
            ),
            isTombstone = false,
        )
}

package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.testing.TagSyncEngineScope
import com.calypsan.listenup.client.data.sync.testing.withTagSyncEngineAgainstServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e tests for the `tags` and `book_tags` sync domains.
 *
 * Writes on the server's [com.calypsan.listenup.server.sync.TagRepository] and
 * [com.calypsan.listenup.server.sync.BookTagRepository] cross the live SSE firehose;
 * the client [SyncEngine] routes them through the real
 * [com.calypsan.listenup.client.data.sync.domains.tagsDomain] handler and
 * [com.calypsan.listenup.client.data.sync.domains.bookTagsDomain], and the
 * rows land in the client's Room database — exactly the round-trip production performs.
 *
 * The [withTagSyncEngineAgainstServer] harness registers the real tag handlers rather
 * than the [com.calypsan.listenup.client.data.sync.testing.RecordingTagSyncDomainHandler]
 * used by the generic engine fixture, so assertions go directly against Room
 * (via [TagSyncEngineScope.clientDatabase]) rather than a recording buffer.
 *
 * Async waits poll real Room queries inside [withTimeout] — matching the idiom
 * established by [com.calypsan.listenup.client.libraries.LibrariesSyncE2ETest].
 */
class TagSyncE2ETest :
    FunSpec({

        test("server tag upsert → SSE → client Room has the tag") {
            withTagSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                tagRepo.upsert(tag("tag-1", "Science Fiction", "science-fiction"))

                val entity = awaitClientTag(clientDatabase, "tag-1", ROUND_TRIP_TIMEOUT_SECONDS.seconds)
                entity.name shouldBe "Science Fiction"
                entity.slug shouldBe "science-fiction"
                entity.deletedAt shouldBe null
            }
        }

        test("server book_tag upsert → SSE → client Room has the junction row") {
            withTagSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                tagRepo.upsert(tag("tag-2", "Fantasy", "fantasy"))
                awaitClientTag(clientDatabase, "tag-2", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                bookTagRepo.upsert(bookTagPayload("book-a", "tag-2"))

                val junction =
                    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                        var row = clientDatabase.bookTagDao().findByKey("book-a", "tag-2")
                        while (row == null) {
                            row = clientDatabase.bookTagDao().findByKey("book-a", "tag-2")
                        }
                        row
                    }
                junction.bookId shouldBe "book-a"
                junction.tagId shouldBe "tag-2"
                junction.deletedAt shouldBe null
            }
        }

        test("server tag soft-delete → SSE → tag is tombstoned in client Room") {
            withTagSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                tagRepo.upsert(tag("tag-3", "Mystery", "mystery"))
                awaitClientTag(clientDatabase, "tag-3", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                tagRepo.softDelete("tag-3", clientOpId = null)

                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.tagDao().getById("tag-3") != null) {
                        // Poll until the tombstone hides the tag from live queries.
                    }
                }
                // getById excludes tombstoned rows.
                clientDatabase.tagDao().getById("tag-3").shouldBeNull()
            }
        }

        test("server book_tag soft-delete → SSE → junction is tombstoned in client Room") {
            withTagSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                tagRepo.upsert(tag("tag-4", "Non-Fiction", "non-fiction"))
                awaitClientTag(clientDatabase, "tag-4", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                bookTagRepo.upsert(bookTagPayload("book-b", "tag-4"))
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    var row = clientDatabase.bookTagDao().findByKey("book-b", "tag-4")
                    while (row == null) {
                        row = clientDatabase.bookTagDao().findByKey("book-b", "tag-4")
                    }
                }

                bookTagRepo.softDelete("book-b", "tag-4", clientOpId = null)

                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.bookTagDao().findByKey("book-b", "tag-4")?.deletedAt == null) {
                        // Poll until the tombstone arrives.
                    }
                }
                clientDatabase
                    .bookTagDao()
                    .findByKey("book-b", "tag-4")
                    ?.deletedAt
                    .shouldNotBeNull()
            }
        }

        test("multiple tags upserted → all arrive in client Room via catch-up") {
            withTagSyncEngineAgainstServer {
                // Pre-populate before engine start so they arrive via catch-up, not SSE tail.
                tagRepo.upsert(tag("tag-5", "Thriller", "thriller"))
                tagRepo.upsert(tag("tag-6", "Biography", "biography"))
                tagRepo.upsert(tag("tag-7", "History", "history"))

                engine.start(currentUserId = "u1")

                awaitClientTag(clientDatabase, "tag-5", ROUND_TRIP_TIMEOUT_SECONDS.seconds)
                awaitClientTag(clientDatabase, "tag-6", ROUND_TRIP_TIMEOUT_SECONDS.seconds)
                awaitClientTag(clientDatabase, "tag-7", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                clientDatabase.tagDao().getById("tag-5")?.name shouldBe "Thriller"
                clientDatabase.tagDao().getById("tag-6")?.name shouldBe "Biography"
                clientDatabase.tagDao().getById("tag-7")?.name shouldBe "History"
            }
        }
    })

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Polls the client Room DB until the tag [id] is present (live, non-tombstoned),
 * or fails after [timeout].
 */
private suspend fun awaitClientTag(
    database: ListenUpDatabase,
    id: String,
    timeout: kotlin.time.Duration,
) = withTimeout(timeout) {
    var entity = database.tagDao().getById(id)
    while (entity == null) {
        entity = database.tagDao().getById(id)
    }
    entity
}

private fun tag(
    id: String,
    name: String,
    slug: String,
): Tag {
    val now = System.currentTimeMillis()
    return Tag(
        id = id,
        name = name,
        slug = slug,
        revision = 0L,
        updatedAt = now,
        deletedAt = null,
    )
}

private fun bookTagPayload(
    bookId: String,
    tagId: String,
): BookTagSyncPayload {
    val now = System.currentTimeMillis()
    return BookTagSyncPayload(
        id = "$bookId:$tagId",
        bookId = bookId,
        tagId = tagId,
        createdAt = now,
        revision = 0L,
        deletedAt = null,
    )
}

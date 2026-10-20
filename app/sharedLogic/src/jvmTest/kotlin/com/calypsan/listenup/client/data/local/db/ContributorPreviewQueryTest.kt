package com.calypsan.listenup.client.data.local.db

import androidx.room3.useReaderConnection
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import kotlinx.coroutines.flow.first
import kotlin.time.measureTime

/**
 * The covers a wide Library people row shows (spec §3.1.8 c7): each contributor's two most recently added books in a
 * role, newest first, from one query for the whole list.
 */
class ContributorPreviewQueryTest :
    FunSpec({
        fun contributor(id: String) =
            ContributorEntity(
                id = ContributorId(id),
                name = id.replaceFirstChar { it.uppercase() },
                description = null,
                imagePath = null,
                createdAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            )

        fun credit(
            book: String,
            contributorId: String,
            role: String,
        ) = BookContributorCrossRef(bookId = BookId(book), contributorId = ContributorId(contributorId), role = role)

        suspend fun ListenUpDatabase.previews(role: String) =
            contributorDao().observePreviewBookIdsByRole(role).first().map { it.contributorId to it.bookId }

        test("each author's two most recently added books, newest first, ignoring their narrations") {
            withHeldBookDb { db ->
                listOf("old" to 1_000L, "mid" to 2_000L, "new" to 3_000L, "narrated" to 4_000L).forEach { (id, at) ->
                    HeldBookFixture.seedBook(db, id, createdAt = at)
                    HeldBookFixture.publish(db, id)
                }
                db.contributorDao().upsert(contributor("ann"))
                listOf("old", "mid", "new").forEach { book -> db.bookContributorDao().insert(credit(book, "ann", "author")) }
                db.bookContributorDao().insert(credit("narrated", "ann", "narrator"))

                db.previews("author") shouldContainExactly listOf("ann" to "new", "ann" to "mid")
                db.previews("narrator") shouldContainExactly listOf("ann" to "narrated")
            }
        }

        test("a held book never fronts a contributor, and a deleted one never does") {
            withHeldBookDb { db ->
                listOf("old" to 1_000L, "mid" to 2_000L, "new" to 3_000L).forEach { (id, at) ->
                    HeldBookFixture.seedBook(db, id, createdAt = at)
                }
                HeldBookFixture.publish(db, "old")
                HeldBookFixture.publish(db, "mid")
                HeldBookFixture.hold(db, "new")
                val mid = db.bookDao().getById(BookId("mid"))!!
                db.bookDao().upsert(mid.copy(deletedAt = 9_000L))
                db.contributorDao().upsert(contributor("ann"))
                listOf("old", "mid", "new").forEach { book -> db.bookContributorDao().insert(credit(book, "ann", "author")) }

                db.previews("author") shouldContainExactly listOf("ann" to "old")
            }
        }

        test("a contributor with one book has one cover, and each contributor gets their own") {
            withHeldBookDb { db ->
                listOf("a1" to 1_000L, "b1" to 2_000L, "b2" to 3_000L).forEach { (id, at) ->
                    HeldBookFixture.seedBook(db, id, createdAt = at)
                    HeldBookFixture.publish(db, id)
                }
                listOf("ann", "bob").forEach { db.contributorDao().upsert(contributor(it)) }
                db.bookContributorDao().insert(credit("a1", "ann", "author"))
                db.bookContributorDao().insert(credit("b1", "bob", "author"))
                db.bookContributorDao().insert(credit("b2", "bob", "author"))

                db.previews("author") shouldContainExactly listOf("ann" to "a1", "bob" to "b2", "bob" to "b1")
            }
        }

        test("the preview query stays cheap on a 5,000-book library") {
            withHeldBookDb { db ->
                val books =
                    (0 until BOOKS).map { i ->
                        BookEntity(
                            id = BookId("book$i"),
                            libraryId = LibraryId("lib1"),
                            folderId = FolderId("folder1"),
                            title = "Book $i",
                            coverHash = null,
                            totalDuration = 3_600_000L,
                            createdAt = Timestamp(i.toLong()),
                            updatedAt = Timestamp(i.toLong()),
                        )
                    }
                db.bookDao().upsertAll(books)
                books.forEach { db.collectionBookDao().upsert(HeldBookFixture.membership(HeldBookFixture.ALL_BOOKS, it.id.value)) }
                db.contributorDao().upsertAll((0 until CONTRIBUTORS).map { contributor("c$it") })
                // Every book has an author; 7,000 more credits split between narrators and co-authors: 12,000 in all.
                val credits =
                    (0 until BOOKS).map { i -> credit("book$i", "c${i % CONTRIBUTORS}", "author") } +
                        (0 until EXTRA_CREDITS).map { i ->
                            credit("book${i % BOOKS}", "c${(i * 7 + 3) % CONTRIBUTORS}", if (i % 2 == 0) "narrator" else "author")
                        }
                db.bookContributorDao().insertAll(credits.distinct())

                val plan =
                    db.useReaderConnection { connection ->
                        connection.usePrepared(
                            "EXPLAIN QUERY PLAN " + CONTRIBUTOR_PREVIEWS_BY_ROLE_SQL.replace(":role", "'author'"),
                        ) { statement ->
                            buildList { while (statement.step()) add(statement.getText(3)) }
                        }
                    }
                println("ContributorPreviewQueryTest EXPLAIN QUERY PLAN:\n" + plan.joinToString("\n"))

                db.previews("author") // warm
                val timings =
                    (1..RUNS).map { measureTime { db.previews("author") }.inWholeMilliseconds }.sorted()
                val median = timings[RUNS / 2]
                println("ContributorPreviewQueryTest median over $RUNS runs: ${median}ms (all: $timings)")
                median shouldBeLessThan BUDGET_MS
            }
        }
    })

private const val BOOKS = 5_000
private const val CONTRIBUTORS = 1_200
private const val EXTRA_CREDITS = 7_000
private const val RUNS = 5

/**
 * A regression guard, not the decision line. Library PR 6b measured a 11ms median on JVM against a 20ms threshold for
 * adding a `(role, contributorId)` index, so no index was added; this budget leaves room for slow CI runners while
 * still catching a plan that stops using the primary key.
 */
private const val BUDGET_MS = 100L

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The `book_ratings` sync domain is access-filtered on every read path, exactly like
 * `book_moods`/`book_tags`: a rating is visible iff its book is. See [BookAccessPolicy]'s
 * `accessibleBookRatingIdsSql`, and the wiring into [ACCESS_FILTERS] and the live firehose gate
 * ([BookRatingsFirehoseAccessTest] pins the latter).
 */
class BookRatingAccessTest :
    FunSpec({
        test("the filtered pull serves ratings only on books the member can open") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("open")
                sql.seedTestBook("hidden")
                sql.seedTestUser("rater")
                sql.seedTestUser("viewer")
                makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                val repo = BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    for (book in listOf("open", "hidden")) {
                        repo.upsert(
                            BookRatingSyncPayload(
                                id = "r-$book",
                                bookId = book,
                                userId = "rater",
                                halfStars = 8,
                                note = null,
                                ratedAt = 1L,
                                updatedAt = 1L,
                                revision = 0L,
                            ),
                        )
                    }
                    val filter =
                        accessFilterFor("book_ratings", "viewer", UserRole.MEMBER) { BookAccessPolicy(sql, driver) }

                    repo.pullSince(userId = "viewer", cursor = 0L, limit = 10, extraWhere = filter).items.map {
                        it.bookId
                    } shouldBe listOf("open")
                }
            }
        }

        test("book_ratings is a per-row access-gated domain") {
            ("book_ratings" in perRowAccessGatedSyncDomains) shouldBe true
        }
    })

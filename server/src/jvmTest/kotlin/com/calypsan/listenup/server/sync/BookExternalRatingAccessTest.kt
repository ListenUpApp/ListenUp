package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.ExternalRatingSource
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
 * The `book_external_ratings` sync domain is access-filtered on every read path, exactly like
 * `book_ratings`: an outside rating is visible iff its book is. See [BookAccessPolicy]'s
 * `accessibleBookExternalRatingIdsSql`, and the wiring into [ACCESS_FILTERS] and the live firehose
 * gate ([BookExternalRatingsFirehoseAccessTest] pins the latter).
 */
class BookExternalRatingAccessTest :
    FunSpec({
        test("the filtered pull serves outside ratings only on books the member can open") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("open", asin = "B-OPEN")
                sql.seedTestBook("hidden", asin = "B-HIDDEN")
                sql.seedTestUser("viewer")
                makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                val repo =
                    BookExternalRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    for (book in listOf("open", "hidden")) {
                        repo.recordFetch(
                            bookId = book,
                            source = ExternalRatingSource.AUDIBLE,
                            average = 4.2,
                            count = 100,
                            region = "us",
                            fetchedAt = 1_000L,
                        )
                    }
                    val filter =
                        accessFilterFor("book_external_ratings", "viewer", UserRole.MEMBER) {
                            BookAccessPolicy(sql, driver)
                        }

                    repo.pullSince(userId = "viewer", cursor = 0L, limit = 10, extraWhere = filter).items.map {
                        it.bookId
                    } shouldBe listOf("open")

                    // The digest a member reconciles against must count only their visible rating —
                    // otherwise they reconcile forever against a row the server will never send them.
                    repo.digest(userId = "viewer", cursor = 100L, extraWhere = filter).count shouldBe 1
                    repo.digest(userId = null, cursor = 100L, extraWhere = null).count shouldBe 2

                    // The targeted BOOK_ID match (the scoped AccessChanged delta) must omit the
                    // hidden book's rating for a member, even when they ask for it by id.
                    repo
                        .pullByIds(
                            userId = "viewer",
                            matchColumn = "book_id",
                            matchValues = listOf("open", "hidden"),
                            extraWhere = filter,
                        ).items
                        .map { it.bookId } shouldBe listOf("open")
                }
            }
        }

        test("book_external_ratings is a per-row access-gated domain") {
            ("book_external_ratings" in perRowAccessGatedSyncDomains) shouldBe true
        }

        test("book_external_ratings supports the BOOK_ID targeted match") {
            ("book_external_ratings" in BOOK_ID_MATCH_DOMAINS) shouldBe true
        }
    })

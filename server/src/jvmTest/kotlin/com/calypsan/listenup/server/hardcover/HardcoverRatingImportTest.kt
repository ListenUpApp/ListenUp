package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.api.sync.ListenerRatingSource
import com.calypsan.listenup.server.sync.BookRatingRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private const val USER = "u1"
private const val BOOK = "book-1"

// The Hardcover book PullRig seeds as BOOK's edition.
private const val HC_BOOK = 427_578L

/** The Hardcover rating import: the half-star mapping, privacy, and the fill-gaps table. */
class HardcoverRatingImportTest :
    FunSpec({
        test("Hardcover's rating maps onto ListenUp's half stars; none and zero are no rating") {
            hardcoverHalfStars(0.5) shouldBe 2
            hardcoverHalfStars(3.5) shouldBe 7
            hardcoverHalfStars(5.0) shouldBe 10
            hardcoverHalfStars(null) shouldBe null
            hardcoverHalfStars(0.0) shouldBe null
        }

        test("the pull's shelf entry carries the rating Hardcover sent") {
            pullTest {
                connect()
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, editionId = 9_001L)
                hardcover.rate(HC_BOOK, 4.5)

                val entry =
                    userBooks
                        .changedSince("hc_at_1", PULL_EPOCH, 0L, PULL_PAGE_SIZE)
                        .shouldBeInstanceOf<HardcoverCall.Ok<List<HardcoverShelfEntry>>>()
                        .value
                        .single()

                entry.ratingHalfStars shouldBe 9
                entry.sharedRatingHalfStars shouldBe 9
            }
        }

        test("only a public rating is shared: followers-only and private ones read as no rating") {
            pullTest {
                connect()
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, editionId = 9_001L)
                hardcover.rate(HC_BOOK, 4.5)

                for ((setting, shared) in listOf(HardcoverPrivacy.PUBLIC to 9, 2 to null, 3 to null)) {
                    hardcover.setPrivacy(HC_BOOK, setting)
                    val entry =
                        userBooks
                            .changedSince("hc_at_1", PULL_EPOCH, 0L, PULL_PAGE_SIZE)
                            .shouldBeInstanceOf<HardcoverCall.Ok<List<HardcoverShelfEntry>>>()
                            .value
                            .single()
                    entry.privacySettingId shouldBe setting
                    entry.sharedRatingHalfStars shouldBe shared
                }
            }
        }

        // One rig per test: a user, a book, a rating repository, and the import under test.
        fun importTest(block: suspend ImportRig.() -> Unit) = withSqlDatabase { runTest { ImportRig(this@withSqlDatabase).block() } }

        test("no rating yet: Hardcover's fills the gap, marked as from Hardcover") {
            importTest {
                ratingImport.apply(USER, BOOK, 9) shouldBe AppResult.Success(Unit)
                mine()!!.halfStars shouldBe 9
                mine()!!.source shouldBe ListenerRatingSource.HARDCOVER
            }
        }

        test("no rating and none on Hardcover: nothing is written") {
            importTest {
                ratingImport.apply(USER, BOOK, null)
                mine() shouldBe null
            }
        }

        test("an imported rating follows Hardcover: a change updates it, a removal clears it") {
            importTest {
                ratingImport.apply(USER, BOOK, 9)
                ratingImport.apply(USER, BOOK, 6)
                mine()!!.halfStars shouldBe 6

                ratingImport.apply(USER, BOOK, null)
                mine() shouldBe null
            }
        }

        test("a rating set in ListenUp is never overwritten, whatever Hardcover says") {
            importTest {
                ratings.upsert(rating(halfStars = 4))
                ratingImport.apply(USER, BOOK, 10)
                ratingImport.apply(USER, BOOK, null)
                mine()!!.halfStars shouldBe 4
                mine()!!.source shouldBe ListenerRatingSource.LISTENUP
            }
        }

        test("a cleared rating stays cleared while Hardcover's is unchanged, and returns when it changes") {
            importTest {
                ratingImport.apply(USER, BOOK, 9)
                ratings.clear(BOOK, USER)

                ratingImport.apply(USER, BOOK, 9)
                mine() shouldBe null

                ratingImport.apply(USER, BOOK, 7)
                mine()!!.halfStars shouldBe 7
                mine()!!.source shouldBe ListenerRatingSource.HARDCOVER
            }
        }

        test("a rating cleared before the pull ever looked is a baseline, not an import") {
            importTest {
                ratings.upsert(rating(halfStars = 5))
                ratings.clear(BOOK, USER)

                ratingImport.apply(USER, BOOK, 8)
                mine() shouldBe null

                ratingImport.apply(USER, BOOK, 6)
                mine()!!.halfStars shouldBe 6
            }
        }

        test("Hardcover removing and re-adding a rating after a clear counts as a change") {
            importTest {
                ratingImport.apply(USER, BOOK, 9)
                ratings.clear(BOOK, USER)
                ratingImport.apply(USER, BOOK, null)
                ratingImport.apply(USER, BOOK, 9)
                mine()!!.halfStars shouldBe 9
            }
        }
    })

/** A user and a library book, with the rating repository and the import under test over them. */
internal class ImportRig(
    dbs: SqlTestDatabases,
) {
    val sql = dbs.sql
    val ratings = BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = dbs.driver)
    val ratingImport = HardcoverRatingImport(sql = sql, ratings = ratings)

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook(BOOK)
    }

    suspend fun mine(): BookRatingSyncPayload? = ratings.findForBook(BOOK).singleOrNull { it.userId == USER }

    fun rating(halfStars: Int) =
        BookRatingSyncPayload(
            id = "cand-1",
            bookId = BOOK,
            userId = USER,
            halfStars = halfStars,
            note = null,
            ratedAt = 0L,
            updatedAt = 0L,
            revision = 0L,
        )
}

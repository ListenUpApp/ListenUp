package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

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
    })

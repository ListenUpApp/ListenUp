@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoice
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoiceKind
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.shouldFailWith
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/** Choosing and clearing a reading order per series, and the follower count (#962). */
class ReadingOrderFollowTest :
    FunSpec({
        test("anyone may follow a built-in on any live series; the row is <user>:<series> and reaches only them") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess")
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    deps
                        .serviceAs("jess")
                        .chooseReadingOrder(ids.cosmere, ReadingOrderChoice.PublicationOrder)
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.follows
                        .pullSince(userId = "jess", cursor = 0, limit = 50)
                        .items
                        .single()
                        .let { follow ->
                            follow.id shouldBe "jess:${ids.cosmere.value}"
                            follow.seriesId shouldBe ids.cosmere.value
                            follow.choice shouldBe ReadingOrderChoiceKind.PUBLICATION
                            follow.readingOrderId shouldBe null
                        }
                    deps.follows
                        .pullSince(userId = "priya", cursor = 0, limit = 50)
                        .items
                        .shouldBeEmpty()
                }
            }
        }

        test("a member may follow a parent's order on a sub-series, but not a child's order on the parent") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess")
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val simon = deps.serviceAs("simon", UserRole.ADMIN)
                    simon.createReadingOrder(ReadingOrderId("ro"), ids.cosmere, "URO")
                    simon.createReadingOrder(ReadingOrderId("mb"), ids.mistborn, "Mistborn with novellas")
                    val jess = deps.serviceAs("jess")
                    jess
                        .chooseReadingOrder(ids.mistborn, ReadingOrderChoice.UserMade(ReadingOrderId("ro")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    jess
                        .chooseReadingOrder(ids.cosmere, ReadingOrderChoice.UserMade(ReadingOrderId("ro")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    jess
                        .chooseReadingOrder(ids.cosmere, ReadingOrderChoice.UserMade(ReadingOrderId("mb")))
                        .shouldFailWith<ReadingOrderError.ChoiceUnavailable>()
                }
            }
        }

        test("following a missing order, or one on an unrelated series, is ChoiceUnavailable; a missing series NotFound") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess")
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    deps.serviceAs("simon", UserRole.ADMIN).createReadingOrder(ReadingOrderId("dw"), ids.discworld, "Watch")
                    val jess = deps.serviceAs("jess")
                    jess
                        .chooseReadingOrder(ids.cosmere, ReadingOrderChoice.UserMade(ReadingOrderId("ghost")))
                        .shouldFailWith<ReadingOrderError.ChoiceUnavailable>()
                    jess
                        .chooseReadingOrder(ids.cosmere, ReadingOrderChoice.UserMade(ReadingOrderId("dw")))
                        .shouldFailWith<ReadingOrderError.ChoiceUnavailable>()
                    jess
                        .chooseReadingOrder(SeriesId("nope"), ReadingOrderChoice.SeriesOrder)
                        .shouldFailWith<ReadingOrderError.NotFound>()
                }
            }
        }

        test("clear tombstones the row and is idempotent; choosing again revives the same id") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess")
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val jess = deps.serviceAs("jess")
                    val followId = "jess:${ids.cosmere.value}"
                    jess.clearReadingOrderChoice(ids.cosmere).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    jess.chooseReadingOrder(ids.cosmere, ReadingOrderChoice.PublicationOrder)
                    jess.clearReadingOrderChoice(ids.cosmere).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.follows.findLive(followId) shouldBe null
                    val revision =
                        deps.follows
                            .pullSince(userId = "jess", cursor = 0, limit = 5)
                            .items
                            .single()
                            .revision
                    jess.clearReadingOrderChoice(ids.cosmere).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.follows
                        .pullSince(userId = "jess", cursor = 0, limit = 5)
                        .items
                        .single()
                        .revision shouldBe revision
                    jess.chooseReadingOrder(ids.cosmere, ReadingOrderChoice.SeriesOrder)
                    deps.follows.findLive(followId)!!.choice shouldBe ReadingOrderChoiceKind.SERIES
                }
            }
        }

        test("countReadingOrderFollowers counts every user's live follow; only the maker or an admin may ask") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess", canMakeReadingOrders = true)
                sql.seedTestUser("priya")
                sql.seedTestUser("sam")
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val jess = deps.serviceAs("jess")
                    jess.createReadingOrder(ReadingOrderId("ro"), ids.cosmere, "Jess's order")
                    val made = ReadingOrderChoice.UserMade(ReadingOrderId("ro"))
                    deps.serviceAs("priya").chooseReadingOrder(ids.cosmere, made)
                    deps.serviceAs("sam").chooseReadingOrder(ids.mistborn, made)
                    jess.countReadingOrderFollowers(ReadingOrderId("ro")) shouldBe AppResult.Success(2)
                    deps.serviceAs("simon", UserRole.ADMIN).countReadingOrderFollowers(ReadingOrderId("ro")) shouldBe
                        AppResult.Success(2)
                    deps
                        .serviceAs("priya")
                        .countReadingOrderFollowers(ReadingOrderId("ro"))
                        .shouldFailWith<ReadingOrderError.Forbidden>()
                }
            }
        }
    })

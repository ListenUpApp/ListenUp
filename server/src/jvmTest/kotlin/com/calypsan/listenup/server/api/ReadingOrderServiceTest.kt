@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoice
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.shouldFailWith
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/** Making, renaming and deleting reading orders (#962), and their dormancy on a dead series. */
class ReadingOrderServiceTest :
    FunSpec({
        test("an admin makes an order on a series; replaying the same id is Success and changes nothing") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val svc = deps.serviceAs("simon", UserRole.ADMIN)
                    svc
                        .createReadingOrder(ReadingOrderId("ro"), ids.cosmere, " Ultimate Read Order ")
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val revision = deps.orders.findLive("ro")!!.revision
                    svc
                        .createReadingOrder(ReadingOrderId("ro"), ids.cosmere, "Something else")
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.orders.findLive("ro")!!.let { order ->
                        order.name shouldBe "Ultimate Read Order"
                        order.createdBy shouldBe "simon"
                        order.seriesId shouldBe ids.cosmere.value
                        order.revision shouldBe revision
                    }
                }
            }
        }

        test("names: blank and 81 chars are InvalidName; a case and space twin on one series exists already; another series may reuse it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val svc = deps.serviceAs("simon", UserRole.ADMIN)
                    svc.createReadingOrder(ReadingOrderId("a"), ids.cosmere, " ").shouldFailWith<ReadingOrderError.InvalidName>()
                    svc
                        .createReadingOrder(ReadingOrderId("a"), ids.cosmere, "x".repeat(81))
                        .shouldFailWith<ReadingOrderError.InvalidName>()
                    svc.createReadingOrder(ReadingOrderId("a"), ids.cosmere, "URO").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    svc
                        .createReadingOrder(ReadingOrderId("b"), ids.cosmere, " uro ")
                        .shouldFailWith<ReadingOrderError.NameAlreadyExists>()
                    svc.createReadingOrder(ReadingOrderId("c"), ids.mistborn, "URO").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    svc.renameReadingOrder(ReadingOrderId("c"), "uro").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.orders.findLive("c")!!.name shouldBe "uro"
                    svc.createReadingOrder(ReadingOrderId("d"), ids.cosmere, "Other").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    svc.renameReadingOrder(ReadingOrderId("d"), "URO ").shouldFailWith<ReadingOrderError.NameAlreadyExists>()
                    svc.renameReadingOrder(ReadingOrderId("d"), "").shouldFailWith<ReadingOrderError.InvalidName>()
                }
            }
        }

        test("a missing or deleted series is NotFound; someone else's id is Forbidden; my own deleted id is NotFound") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess", canMakeReadingOrders = true)
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val simon = deps.serviceAs("simon", UserRole.ADMIN)
                    simon.createReadingOrder(ReadingOrderId("x"), SeriesId("nope"), "A").shouldFailWith<ReadingOrderError.NotFound>()
                    deps.hierarchy.service
                        .deleteSeries(ids.discworld)
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    simon.createReadingOrder(ReadingOrderId("x"), ids.discworld, "A").shouldFailWith<ReadingOrderError.NotFound>()

                    simon.createReadingOrder(ReadingOrderId("a"), ids.cosmere, "URO").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps
                        .serviceAs("jess")
                        .createReadingOrder(ReadingOrderId("a"), ids.cosmere, "Mine")
                        .shouldFailWith<ReadingOrderError.Forbidden>()

                    simon.deleteReadingOrder(ReadingOrderId("a")).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    simon.createReadingOrder(ReadingOrderId("a"), ids.cosmere, "URO").shouldFailWith<ReadingOrderError.NotFound>()
                }
            }
        }

        test("deleting an order tombstones it, its memberships and every user's follow of it, in one go") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess")
                sql.seedTestUser("priya")
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val simon = deps.serviceAs("simon", UserRole.ADMIN)
                    simon.createReadingOrder(ReadingOrderId("ro"), ids.cosmere, "URO")
                    simon.addBookToReadingOrder(ReadingOrderId("ro"), BookId("tfe"), "m1").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    simon.addBookToReadingOrder(ReadingOrderId("ro"), BookId("elantris"), "m2")
                    val uro = ReadingOrderChoice.UserMade(ReadingOrderId("ro"))
                    deps.serviceAs("jess").chooseReadingOrder(ids.cosmere, uro).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.serviceAs("priya").chooseReadingOrder(ids.mistborn, uro).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.serviceAs("priya").chooseReadingOrder(ids.cosmere, ReadingOrderChoice.PublicationOrder)

                    simon.deleteReadingOrder(ReadingOrderId("ro")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.orders.findLive("ro") shouldBe null
                    deps.members.liveMembers("ro").shouldBeEmpty()
                    deps.follows.liveFollowersOf("ro").shouldBeEmpty()
                    // A follow of something else is untouched.
                    deps.follows.findLive("priya:${ids.cosmere.value}")!!.readingOrderId shouldBe null
                    // Each follower's tombstone reaches that follower.
                    deps.follows
                        .pullSince(userId = "jess", cursor = 0, limit = 50)
                        .items
                        .single()
                        .deletedAt
                        .shouldBeInstanceOf<Long>()
                    simon.deleteReadingOrder(ReadingOrderId("ro")).shouldFailWith<ReadingOrderError.NotFound>()
                }
            }
        }

        test("orders on a deleted series are dormant: writes are NotFound, and the order is back when the series revives") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val simon = deps.serviceAs("simon", UserRole.ADMIN)
                    simon.createReadingOrder(ReadingOrderId("ro"), ids.discworld, "Watch")
                    deps.hierarchy.service
                        .deleteSeries(ids.discworld)
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    simon.renameReadingOrder(ReadingOrderId("ro"), "City Watch").shouldFailWith<ReadingOrderError.NotFound>()
                    deps.orders.findLive("ro")!!.name shouldBe "Watch"

                    deps.hierarchy.seriesRepo.resolveOrCreate("Discworld") shouldBe ids.discworld
                    simon.renameReadingOrder(ReadingOrderId("ro"), "City Watch").shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("a series deleted under a parent leaves the parent's orders alone") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("simon", UserRoleColumn.ADMIN)
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val simon = deps.serviceAs("simon", UserRole.ADMIN)
                    simon.createReadingOrder(ReadingOrderId("ro"), ids.cosmere, "URO")
                    deps.hierarchy.service
                        .deleteSeries(ids.mistborn)
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    simon.renameReadingOrder(ReadingOrderId("ro"), "URO 2").shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }
    })

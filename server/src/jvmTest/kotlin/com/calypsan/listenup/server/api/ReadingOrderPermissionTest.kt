@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoice
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.shouldFailWith
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/** The "Can make reading orders" permission end to end (#962). */
class ReadingOrderPermissionTest :
    FunSpec({
        test("a member without the permission gets PermissionDenied on create, but may still follow") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess", canMakeReadingOrders = false)
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val jess = deps.serviceAs("jess")
                    jess.createReadingOrder(ReadingOrderId("ro"), ids.cosmere, "Mine").shouldFailWith<AuthError.PermissionDenied>()
                    deps.orders.findAny("ro") shouldBe null
                    jess
                        .chooseReadingOrder(ids.cosmere, ReadingOrderChoice.PublicationOrder)
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("a member holds the permission by default — additive, undoable work defaults on") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.usersQueries.insert(
                    id = "fresh",
                    email = "fresh@example.com",
                    email_normalized = "fresh@example.com",
                    password_hash = "phc",
                    role = "MEMBER",
                    display_name = "fresh",
                    status = "ACTIVE",
                    created_at = 1L,
                    updated_at = 1L,
                    last_login_at = null,
                    can_edit = 1L,
                    approved_by = null,
                    approved_at = null,
                    deleted_at = null,
                    invited_by = null,
                    tagline = null,
                    avatar_type = "auto",
                    timezone = "UTC",
                )
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    deps
                        .serviceAs("fresh")
                        .createReadingOrder(ReadingOrderId("ro"), ids.cosmere, "Mine")
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("a granted member edits their own order; another granted member gets Forbidden on it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess", canMakeReadingOrders = true)
                sql.seedTestUser("priya", canMakeReadingOrders = true)
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    makeBookAccessible(sql, driver, bookId = "tfe", viewerId = "jess")
                    val jess = deps.serviceAs("jess")
                    val priya = deps.serviceAs("priya")
                    val ro = ReadingOrderId("ro")
                    jess.createReadingOrder(ro, ids.cosmere, "Jess's order").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    jess.addBookToReadingOrder(ro, BookId("tfe"), "m1").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    jess.renameReadingOrder(ro, "Jess's URO").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    priya.renameReadingOrder(ro, "Mine now").shouldFailWith<ReadingOrderError.Forbidden>()
                    priya.addBookToReadingOrder(ro, BookId("woa"), "m2").shouldFailWith<ReadingOrderError.Forbidden>()
                    priya.removeBookFromReadingOrder(ro, BookId("tfe")).shouldFailWith<ReadingOrderError.Forbidden>()
                    priya.reorderReadingOrder(ro, emptyList()).shouldFailWith<ReadingOrderError.Forbidden>()
                    priya.deleteReadingOrder(ro).shouldFailWith<ReadingOrderError.Forbidden>()
                    deps.orders.findLive("ro")!!.name shouldBe "Jess's URO"
                }
            }
        }

        test("after the permission is revoked the maker gets Forbidden; an admin can still edit; followers keep following") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess", canMakeReadingOrders = true)
                sql.seedTestUser("priya")
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val ro = ReadingOrderId("ro")
                    deps.serviceAs("jess").createReadingOrder(ro, ids.cosmere, "Jess's order")
                    deps.serviceAs("priya").chooseReadingOrder(ids.cosmere, ReadingOrderChoice.UserMade(ro))

                    sql.usersQueries.setCanMakeReadingOrders(can_make_reading_orders = 0L, id = "jess")

                    deps.serviceAs("jess").renameReadingOrder(ro, "Still mine").shouldFailWith<ReadingOrderError.Forbidden>()
                    deps
                        .serviceAs("simon", UserRole.ADMIN)
                        .renameReadingOrder(ro, "Renamed by an admin")
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.orders.findLive("ro")!!.createdBy shouldBe "jess"
                    deps.follows.findLive("priya:${ids.cosmere.value}")!!.readingOrderId shouldBe "ro"
                }
            }
        }
    })

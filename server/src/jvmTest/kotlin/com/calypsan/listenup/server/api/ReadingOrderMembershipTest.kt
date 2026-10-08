@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserRole
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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private val RO = ReadingOrderId("ro")

/** Subtree-only membership, idempotent add and remove, and the tolerant reorder (#962). */
class ReadingOrderMembershipTest :
    FunSpec({
        test("add appends books from the order's subtree; re-adding is Success and keeps the place") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val svc = deps.serviceAs("simon", UserRole.ADMIN)
                    svc.createReadingOrder(RO, ids.cosmere, "URO")
                    svc.addBookToReadingOrder(RO, BookId("tfe"), "m1").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    svc.addBookToReadingOrder(RO, BookId("elantris"), "m2").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    svc.addBookToReadingOrder(RO, BookId("tfe"), "m3").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.bookIdsOf("ro") shouldContainExactly listOf("tfe", "elantris")
                    deps.members.liveMembers("ro").map { it.id } shouldContainExactly listOf("m1", "m2")
                }
            }
        }

        test("a book outside the subtree is BookOutsideSeries; a missing book is NotFound") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val svc = deps.serviceAs("simon", UserRole.ADMIN)
                    svc.createReadingOrder(RO, ids.cosmere, "URO")
                    svc.addBookToReadingOrder(RO, BookId("cog"), "m1").shouldFailWith<ReadingOrderError.BookOutsideSeries>()
                    svc.addBookToReadingOrder(RO, BookId("ghost"), "m2").shouldFailWith<ReadingOrderError.NotFound>()
                }
            }
        }

        test("an order on Mistborn can't take Cosmere's own book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val svc = deps.serviceAs("simon", UserRole.ADMIN)
                    svc.createReadingOrder(RO, ids.mistborn, "Mistborn with novellas")
                    svc.addBookToReadingOrder(RO, BookId("elantris"), "m1").shouldFailWith<ReadingOrderError.BookOutsideSeries>()
                    svc.addBookToReadingOrder(RO, BookId("woa"), "m2").shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("a maker can't add a book they can't see — NotFound, never revealing it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess", canMakeReadingOrders = true)
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    makeBookAccessible(sql, driver, bookId = "tfe", viewerId = "jess")
                    val jess = deps.serviceAs("jess")
                    jess.createReadingOrder(RO, ids.mistborn, "Mine").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    jess.addBookToReadingOrder(RO, BookId("tfe"), "m1").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    jess.addBookToReadingOrder(RO, BookId("woa"), "m2").shouldFailWith<ReadingOrderError.NotFound>()
                }
            }
        }

        test("remove is idempotent, and re-adding a removed book puts it back at the end under its old id") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val svc = deps.serviceAs("simon", UserRole.ADMIN)
                    svc.createReadingOrder(RO, ids.cosmere, "URO")
                    svc.addBookToReadingOrder(RO, BookId("tfe"), "m1")
                    svc.addBookToReadingOrder(RO, BookId("woa"), "m2")
                    svc.removeBookFromReadingOrder(RO, BookId("tfe")).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    svc.removeBookFromReadingOrder(RO, BookId("tfe")).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    svc.removeBookFromReadingOrder(RO, BookId("cog")).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.bookIdsOf("ro") shouldContainExactly listOf("woa")
                    svc.addBookToReadingOrder(RO, BookId("tfe"), "m9").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.bookIdsOf("ro") shouldContainExactly listOf("woa", "tfe")
                    deps.members
                        .liveMembers("ro")
                        .last()
                        .id shouldBe "m1"
                }
            }
        }

        test("reorder puts listed members first in the given order, keeps unlisted members after, ignores strangers") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val svc = deps.serviceAs("simon", UserRole.ADMIN)
                    svc.createReadingOrder(RO, ids.cosmere, "URO")
                    listOf("tfe", "woa", "elantris").forEachIndexed { i, b -> svc.addBookToReadingOrder(RO, BookId(b), "m$i") }
                    svc
                        .reorderReadingOrder(RO, listOf("elantris", "ghost", "tfe", "elantris").map(::BookId))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.bookIdsOf("ro") shouldContainExactly listOf("elantris", "tfe", "woa")
                }
            }
        }

        test("a reorder composed before another device added a book still applies, keeping the new book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val svc = deps.serviceAs("simon", UserRole.ADMIN)
                    svc.createReadingOrder(RO, ids.cosmere, "URO")
                    svc.addBookToReadingOrder(RO, BookId("tfe"), "m1")
                    svc.addBookToReadingOrder(RO, BookId("woa"), "m2")
                    // Another device adds elantris; this device's queued reorder only knew tfe and woa.
                    svc.addBookToReadingOrder(RO, BookId("elantris"), "m3")
                    svc.reorderReadingOrder(RO, listOf(BookId("woa"), BookId("tfe"))).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.bookIdsOf("ro") shouldContainExactly listOf("woa", "tfe", "elantris")
                }
            }
        }

        test("a reorder naming only some members moves them first and renumbers the rest after, in their old order") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val svc = deps.serviceAs("simon", UserRole.ADMIN)
                    svc.createReadingOrder(RO, ids.cosmere, "URO")
                    listOf("tfe", "woa", "elantris").forEachIndexed { i, b -> svc.addBookToReadingOrder(RO, BookId(b), "m$i") }
                    svc.reorderReadingOrder(RO, listOf(BookId("elantris"))).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.bookIdsOf("ro") shouldContainExactly listOf("elantris", "tfe", "woa")
                    deps.members.liveMembers("ro").map { it.position } shouldContainExactly listOf(0, 1, 2)
                }
            }
        }

        test("reorder rewrites only rows whose position changed") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val svc = deps.serviceAs("simon", UserRole.ADMIN)
                    svc.createReadingOrder(RO, ids.cosmere, "URO")
                    listOf("tfe", "woa", "elantris").forEachIndexed { i, b -> svc.addBookToReadingOrder(RO, BookId(b), "m$i") }
                    val before = deps.members.liveMembers("ro").associate { it.bookId to it.revision }
                    svc.reorderReadingOrder(RO, listOf("tfe", "elantris", "woa").map(::BookId))
                    val after = deps.members.liveMembers("ro").associate { it.bookId to it.revision }
                    after["tfe"] shouldBe before["tfe"]
                    after["woa"] shouldNotBe before["woa"]
                    after["elantris"] shouldNotBe before["elantris"]
                }
            }
        }

        test("a reorder over 5000 ids is InvalidInput and changes nothing") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val svc = deps.serviceAs("simon", UserRole.ADMIN)
                    svc.createReadingOrder(RO, ids.cosmere, "URO")
                    svc.addBookToReadingOrder(RO, BookId("tfe"), "m1")
                    svc.addBookToReadingOrder(RO, BookId("woa"), "m2")
                    svc
                        .reorderReadingOrder(RO, List(5_001) { BookId("b$it") })
                        .shouldFailWith<ReadingOrderError.InvalidInput>()
                    svc.reorderReadingOrder(RO, List(5_000) { BookId("b$it") }).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.bookIdsOf("ro") shouldContainExactly listOf("tfe", "woa")
                }
            }
        }

        test("tolerantOrder handles an empty request, repeats and all strangers") {
            tolerantOrder(listOf("a", "b"), emptyList()) shouldContainExactly listOf("a", "b")
            tolerantOrder(listOf("a", "b", "c"), listOf("c", "c", "a")) shouldContainExactly listOf("c", "a", "b")
            tolerantOrder(listOf("a", "b"), listOf("x", "y")) shouldContainExactly listOf("a", "b")
            tolerantOrder(emptyList(), listOf("x")) shouldContainExactly emptyList()
        }
    })

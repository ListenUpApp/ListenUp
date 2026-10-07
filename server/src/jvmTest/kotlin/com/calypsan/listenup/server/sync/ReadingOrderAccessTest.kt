@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload
import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
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
 * `reading_order_books` is access-filtered on every read path, like `book_tags` (#962): a membership is
 * visible iff its book is, so an order never tells a member that a restricted book exists. Orders
 * themselves are global; tombstones pass ungated but with the pair blanked.
 */
class ReadingOrderAccessTest :
    FunSpec({
        test("the filtered pull, digest and targeted match serve memberships only for books the member can open") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("open")
                sql.seedTestBook("secret")
                sql.seedTestUser("jess")
                makeBookAccessible(sql, driver, bookId = "open", viewerId = "jess")
                val members = ReadingOrderBookRepository(sql, ChangeBus(), SyncRegistry(), driver)
                runTest {
                    ReadingOrderRepository(sql, ChangeBus(), SyncRegistry()).upsert(order("ro"))
                    members.upsert(member("m-open", "open", 0))
                    members.upsert(member("m-secret", "secret", 1))
                    val filter =
                        accessFilterFor("reading_order_books", "jess", UserRole.MEMBER) { BookAccessPolicy(sql, driver) }

                    members.pullSince(userId = "jess", cursor = 0L, limit = 10, extraWhere = filter).items.map {
                        it.bookId
                    } shouldBe listOf("open")
                    members.digest(userId = "jess", cursor = 100L, extraWhere = filter).count shouldBe 1
                    members.digest(userId = null, cursor = 100L, extraWhere = null).count shouldBe 2
                    members
                        .pullByIds(
                            userId = "jess",
                            matchColumn = "book_id",
                            matchValues = listOf("open", "secret"),
                            extraWhere = filter,
                        ).items
                        .map { it.bookId } shouldBe listOf("open")
                }
            }
        }

        test("an admin's pull is unfiltered") {
            withSqlDatabase {
                (accessFilterFor("reading_order_books", "simon", UserRole.ADMIN) { BookAccessPolicy(sql, driver) }) shouldBe
                    null
            }
        }

        test("the firehose withholds a live membership event for a book the member can't open, and passes tombstones") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("open")
                sql.seedTestBook("secret")
                sql.seedTestUser("jess")
                makeBookAccessible(sql, driver, bookId = "open", viewerId = "jess")
                val members = ReadingOrderBookRepository(sql, ChangeBus(), SyncRegistry(), driver)
                val policy = { BookAccessPolicy(sql, driver) }
                runTest {
                    fun created(bookId: String) =
                        BusEvent(members, SyncEvent.Created(id = "m-$bookId", revision = 1, occurredAt = 1, payload = member("m-$bookId", bookId, 0)))
                    firehoseGateReason(created("secret"), "jess", UserRole.MEMBER, policy) shouldBe "bookJunction"
                    firehoseGateReason(created("open"), "jess", UserRole.MEMBER, policy) shouldBe null
                    firehoseGateReason(created("secret"), "simon", UserRole.ADMIN, policy) shouldBe null
                    val deleted = BusEvent(members, SyncEvent.Deleted(id = "m-secret", revision = 2, occurredAt = 2))
                    firehoseGateReason(deleted, "jess", UserRole.MEMBER, policy) shouldBe null
                }
            }
        }

        test("reading_order_books is per-row access-gated and supports the BOOK_ID targeted match") {
            ("reading_order_books" in perRowAccessGatedSyncDomains) shouldBe true
            ("reading_order_books" in BOOK_ID_MATCH_DOMAINS) shouldBe true
            ("reading_orders" in perRowAccessGatedSyncDomains) shouldBe false
        }
    })

private fun order(id: String) = ReadingOrderSyncPayload(id, "cosmere", "URO", "simon", 0, 0, 0, null)

private fun member(
    id: String,
    bookId: String,
    position: Int,
) = ReadingOrderBookSyncPayload(id, "ro", bookId, position, 0, 0, 0, null)

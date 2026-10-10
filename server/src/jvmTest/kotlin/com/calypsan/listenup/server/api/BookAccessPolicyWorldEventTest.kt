package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.seedSeriesWithBooks
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest

/** Story World event visibility: the home's rule, and the anchor book's. */
class BookAccessPolicyWorldEventTest :
    FunSpec({
        test("an event is visible by its home, and by its anchor book when it has one") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                runTest {
                    val mixed = seedSeriesWithBooks("Mixed", "open", "hidden")
                    makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                    val policy = BookAccessPolicy(sql, driver)
                    suspend fun sees(
                        series: String?,
                        book: String?,
                        anchor: String?,
                    ) = policy.canSeeWorldEvent("viewer", UserRole.MEMBER, series, book, anchor)

                    sees(mixed.value, null, null) shouldBe true
                    sees(mixed.value, null, "open") shouldBe true
                    sees(mixed.value, null, "hidden") shouldBe false
                    sees(null, "open", "open") shouldBe true
                    sees(null, "hidden", null) shouldBe false
                    policy.canSeeWorldEvent("root", UserRole.ROOT, mixed.value, null, "hidden") shouldBe true
                }
            }
        }

        test("accessibleWorldEventIdsSql is unconstrained for admins and gates home and anchor for members") {
            withSqlDatabase {
                val policy = BookAccessPolicy(sql, driver)
                policy.accessibleWorldEventIdsSql("a", UserRole.ADMIN) shouldBe null
                val member = policy.accessibleWorldEventIdsSql("m", UserRole.MEMBER).shouldNotBeNull()
                member.sql shouldContain "w.book_id IS NULL OR w.book_id IN"
                member.args shouldBe List(6) { "m" }
            }
        }
    })

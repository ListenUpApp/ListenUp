package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.seedSeriesWithBooks
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/** Story World visibility: a book-homed entity follows its book; a series-homed one follows any book of its series. */
class BookAccessPolicyEntityTest :
    FunSpec({
        test("a series is visible to a member iff at least one of its books is") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                runTest {
                    val mixed = seedSeriesWithBooks("Mixed", "open", "hidden")
                    val closed = seedSeriesWithBooks("Closed", "hidden2")
                    makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                    val policy = BookAccessPolicy(sql, driver)

                    policy.canAccessSeries("viewer", UserRole.MEMBER, mixed.value) shouldBe true
                    policy.canAccessSeries("viewer", UserRole.MEMBER, closed.value) shouldBe false
                    policy.canAccessSeries("anyone", UserRole.ADMIN, closed.value) shouldBe true
                }
            }
        }

        test("an entity home is visible by its book, or by its series") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                runTest {
                    val mixed = seedSeriesWithBooks("Mixed", "open", "hidden")
                    makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                    val policy = BookAccessPolicy(sql, driver)

                    policy.canSeeEntityHome("viewer", UserRole.MEMBER, homeSeriesId = null, homeBookId = "open") shouldBe true
                    policy.canSeeEntityHome("viewer", UserRole.MEMBER, homeSeriesId = null, homeBookId = "hidden") shouldBe false
                    policy.canSeeEntityHome("viewer", UserRole.MEMBER, homeSeriesId = mixed.value, homeBookId = null) shouldBe true
                    policy.canSeeEntityHome("viewer", UserRole.MEMBER, homeSeriesId = null, homeBookId = null) shouldBe false
                }
            }
        }

        test("accessibleEntityIdsSql is unconstrained for admins") {
            withSqlDatabase {
                BookAccessPolicy(sql, driver).accessibleEntityIdsSql("a", UserRole.ADMIN) shouldBe null
            }
        }
    })

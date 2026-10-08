package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.entityRepository
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.seedSeriesWithBooks
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private val ACTOR = UserId("u1")

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

        test("accessibleEntityIdsSql's member branch: a book home follows its book, a series home any visible book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                val repo = entityRepository()
                runTest {
                    val mixed = seedSeriesWithBooks("Mixed", "open", "hidden")
                    val closed = seedSeriesWithBooks("Closed", "hidden2")
                    val empty = seedSeriesWithBooks("Empty")
                    makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                    repo.upsertEntity(entityPayload("on-open", homeBookId = "open"), ACTOR)
                    repo.upsertEntity(entityPayload("on-hidden", homeBookId = "hidden"), ACTOR)
                    repo.upsertEntity(entityPayload("on-mixed", homeSeriesId = mixed.value), ACTOR)
                    repo.upsertEntity(entityPayload("on-closed", homeSeriesId = closed.value), ACTOR)
                    repo.upsertEntity(entityPayload("on-empty", homeSeriesId = empty.value), ACTOR)
                    val memberFilter = BookAccessPolicy(sql, driver).accessibleEntityIdsSql("viewer", UserRole.MEMBER)

                    repo
                        .pullSince(userId = "viewer", cursor = 0L, limit = 50, extraWhere = memberFilter.shouldNotBeNull())
                        .items
                        .map { it.id }
                        .toSet() shouldBe setOf("on-open", "on-mixed")
                }
            }
        }

        test("a deleted series is hidden from members on both the probe and the pull") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                val repo = entityRepository()
                runTest {
                    val mixed = seedSeriesWithBooks("Mixed", "open")
                    makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                    repo.upsertEntity(entityPayload("on-mixed", homeSeriesId = mixed.value), ACTOR)
                    driver.execute(null, "UPDATE book_series SET deleted_at = 1 WHERE id = ?", 1) { bindString(0, mixed.value) }
                    val policy = BookAccessPolicy(sql, driver)

                    policy.canAccessSeries("viewer", UserRole.MEMBER, mixed.value) shouldBe false
                    repo
                        .pullSince(
                            userId = "viewer",
                            cursor = 0L,
                            limit = 50,
                            extraWhere = policy.accessibleEntityIdsSql("viewer", UserRole.MEMBER),
                        ).items
                        .shouldBeEmpty()
                }
            }
        }
    })

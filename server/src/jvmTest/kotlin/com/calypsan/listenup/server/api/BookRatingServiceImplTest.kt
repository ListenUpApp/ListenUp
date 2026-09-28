package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.RateBookRequest
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.sync.BookRatingRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class BookRatingServiceImplTest :
    FunSpec({
        fun principal(userId: String) = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("s"), UserRole.MEMBER) }

        test("rating writes the caller's own row — the caller comes from the principal, never the request") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"))
                runTest {
                    service
                        .rate(BookId("b1"), RateBookRequest(candidateId = "c1", halfStars = 7, note = " Good. "))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val row = repo.findForBook("b1").single()
                    row.userId shouldBe "u1"
                    row.halfStars shouldBe 7
                    row.note shouldBe "Good."
                }
            }
        }

        test("a book the caller cannot open answers NotFound and writes nothing") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"))
                runTest {
                    val result = service.rate(BookId("b1"), RateBookRequest(candidateId = "c1", halfStars = 7, note = null))

                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<SyncError.NotFound>()
                    repo.findForBook("b1") shouldBe emptyList()
                }
            }
        }

        test("clearing removes only the caller's rating, and clearing twice still succeeds") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u2")
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val policy = BookAccessPolicy(sql, driver)
                runTest {
                    BookRatingServiceImpl(repo, policy, principal("u1")).rate(BookId("b1"), RateBookRequest("c1", 6, null))
                    BookRatingServiceImpl(repo, policy, principal("u2")).rate(BookId("b1"), RateBookRequest("c2", 9, null))

                    val u1 = BookRatingServiceImpl(repo, policy, principal("u1"))
                    u1.clearRating(BookId("b1")).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    u1.clearRating(BookId("b1")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo.findForBook("b1").map { it.userId } shouldBe listOf("u2")
                }
            }
        }
    })

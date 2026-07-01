package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.sync.AdminUserRosterRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class AdminUserRosterMaintainerTest :
    FunSpec({
        test("refresh upserts a roster row carrying the user's admin fields") {
            withSqlDatabase {
                sql.transaction {
                    sql.usersQueries.insert(
                        id = "user-1",
                        email = "ada@example.com",
                        email_normalized = "ada@example.com",
                        password_hash = "phc",
                        role = "MEMBER",
                        display_name = "Ada",
                        status = "ACTIVE",
                        created_at = 1L,
                        updated_at = 1L,
                        last_login_at = null,
                        can_edit = 1L,
                        can_share = 1L,
                        approved_by = null,
                        approved_at = null,
                        deleted_at = null,
                        invited_by = null,
                        tagline = null,
                        avatar_type = "auto",
                        timezone = "UTC",
                    )
                }

                val repo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry())
                val maintainer = AdminUserRosterMaintainer(sql, repo)

                runTest {
                    maintainer.refresh("user-1")

                    val page = repo.pullSince(userId = null, cursor = 0, limit = 100)
                    val saved = page.items.single()
                    saved.id shouldBe "user-1"
                    saved.email shouldBe "ada@example.com"
                    saved.displayName shouldBe "Ada"
                    saved.role shouldBe "MEMBER"
                    saved.status shouldBe "ACTIVE"
                    saved.canShare shouldBe true
                    saved.accountCreatedAt shouldBe 1L
                }
            }
        }

        test("remove tombstones the roster row") {
            withSqlDatabase {
                sql.transaction {
                    sql.usersQueries.insert(
                        id = "user-2",
                        email = "babbage@example.com",
                        email_normalized = "babbage@example.com",
                        password_hash = "phc",
                        role = "MEMBER",
                        display_name = "Babbage",
                        status = "ACTIVE",
                        created_at = 1L,
                        updated_at = 1L,
                        last_login_at = null,
                        can_edit = 1L,
                        can_share = 1L,
                        approved_by = null,
                        approved_at = null,
                        deleted_at = null,
                        invited_by = null,
                        tagline = null,
                        avatar_type = "auto",
                        timezone = "UTC",
                    )
                }

                val repo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry())
                val maintainer = AdminUserRosterMaintainer(sql, repo)

                runTest {
                    maintainer.refresh("user-2")
                    maintainer.remove("user-2")

                    // pullSince returns all rows including soft-deleted ones.
                    val saved = repo.pullSince(userId = null, cursor = 0, limit = 100).items.single()
                    saved.deletedAt.shouldNotBeNull()
                }
            }
        }
    })

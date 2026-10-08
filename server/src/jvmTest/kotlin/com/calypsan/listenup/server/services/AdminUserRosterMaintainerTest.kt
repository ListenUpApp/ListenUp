package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.AdminUserRosterRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestUser
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
                        approved_by = null,
                        approved_at = null,
                        deleted_at = null,
                        invited_by = null,
                        tagline = null,
                        avatar_type = "auto",
                        timezone = "UTC",
                    )
                }

                val repo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
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
                    saved.canEdit shouldBe true
                    saved.accountCreatedAt shouldBe 1L
                }
            }
        }

        test("refresh projects a revoked canEdit flag") {
            // The projection did not carry can_edit until #1270, which is why no admin UI could
            // ever reach canEdit — PermissionPolicy gated every metadata mutation on a flag
            // that existed on `users` and stopped there. A user who may NOT edit is the case a
            // projection that hardcoded the flag would fail.
            withSqlDatabase {
                sql.transaction {
                    sql.usersQueries.insert(
                        id = "user-2",
                        email = "grace@example.com",
                        email_normalized = "grace@example.com",
                        password_hash = "phc",
                        role = "MEMBER",
                        display_name = "Grace",
                        status = "ACTIVE",
                        created_at = 2L,
                        updated_at = 2L,
                        last_login_at = null,
                        can_edit = 0L,
                        approved_by = null,
                        approved_at = null,
                        deleted_at = null,
                        invited_by = null,
                        tagline = null,
                        avatar_type = "auto",
                        timezone = "UTC",
                    )
                }

                val repo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val maintainer = AdminUserRosterMaintainer(sql, repo)

                runTest {
                    maintainer.refresh("user-2")

                    val saved = repo.pullSince(userId = null, cursor = 0, limit = 100).items.single()
                    saved.canEdit shouldBe false
                }
            }
        }

        test("the roster row carries every permission flag, nested, and the flat canEdit for older admin apps") {
            withSqlDatabase {
                sql.seedTestUser("user-3", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = true)
                sql.usersQueries.updateStoryWorldPermissionFlags(
                    can_contribute_story_world = 0L,
                    can_curate_story_world = 1L,
                    id = "user-3",
                )
                val repo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val maintainer = AdminUserRosterMaintainer(sql, repo)

                runTest {
                    maintainer.refresh("user-3")

                    val saved = repo.pullSince(userId = null, cursor = 0, limit = 100).items.single()
                    saved.canEdit shouldBe false
                    saved.permissions shouldBe
                        UserPermissions(
                            canEditMetadata = false,
                            canCurateLibrary = true,
                            canContributeStoryWorld = false,
                            canCurateStoryWorld = true,
                        )
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
                        approved_by = null,
                        approved_at = null,
                        deleted_at = null,
                        invited_by = null,
                        tagline = null,
                        avatar_type = "auto",
                        timezone = "UTC",
                    )
                }

                val repo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
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

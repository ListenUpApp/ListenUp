@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for [UserRoleLookup] — the role resolution behind every signed audio / HLS / cover-cast
 * URL. A signed URL proves *who* but not their role, and it lives for hours, so the lookup must
 * answer for a live account only: a soft-deleted user resolves to no role, which the routes turn
 * into 404 — the moment an account is removed, its already-minted URLs stop working.
 */
class UserRoleLookupTest :
    FunSpec({

        test("roleOf resolves a live user's role") {
            withSqlDatabase {
                sql.seedTestUser("live", UserRoleColumn.ADMIN)
                runTest {
                    UserRoleLookup(sql).roleOf("live") shouldBe UserRole.ADMIN
                }
            }
        }

        test("roleOf resolves a soft-deleted user to null") {
            withSqlDatabase {
                sql.seedTestUser("removed", UserRoleColumn.MEMBER, deletedAt = 1_700_000_000_000L)
                runTest {
                    UserRoleLookup(sql).roleOf("removed").shouldBeNull()
                }
            }
        }

        test("roleOf resolves an unknown id to null") {
            withSqlDatabase {
                runTest {
                    UserRoleLookup(sql).roleOf("nobody").shouldBeNull()
                }
            }
        }
    })

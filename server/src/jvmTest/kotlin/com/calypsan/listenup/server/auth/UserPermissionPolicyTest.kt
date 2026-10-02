@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [UserPermissionPolicy].
 *
 * Real in-memory Flyway-migrated SQLite; no mocks. The policy is the gate that makes
 * the per-user `canEdit` flag live: ROOT/ADMIN implicitly hold every
 * permission (no DB hit); a MEMBER passes iff the flag is set on a live row.
 */
class UserPermissionPolicyTest :
    FunSpec({

        test("ROOT and ADMIN implicitly pass canEdit regardless of the flag") {
            withSqlDatabase {
                val policy = UserPermissionPolicy(sql)
                sql.seedTestUser("a1", UserRoleColumn.ADMIN, canEdit = false)
                sql.seedTestUser("r1", UserRoleColumn.ROOT, canEdit = false)
                runTest {
                    policy.requireCanEdit(UserId("a1"), UserRole.ADMIN) shouldBe null
                    policy.requireCanEdit(UserId("r1"), UserRole.ROOT) shouldBe null
                }
            }
        }

        test("MEMBER is denied canEdit when the flag is false") {
            withSqlDatabase {
                val policy = UserPermissionPolicy(sql)
                sql.seedTestUser("m1", UserRoleColumn.MEMBER, canEdit = false)
                runTest {
                    policy
                        .requireCanEdit(UserId("m1"), UserRole.MEMBER)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("MEMBER with canEdit=true passes canEdit") {
            withSqlDatabase {
                val policy = UserPermissionPolicy(sql)
                sql.seedTestUser("m2", UserRoleColumn.MEMBER, canEdit = true)
                runTest {
                    policy.requireCanEdit(UserId("m2"), UserRole.MEMBER) shouldBe null
                }
            }
        }

        test("soft-deleted MEMBER is denied even with canEdit true") {
            withSqlDatabase {
                val policy = UserPermissionPolicy(sql)
                sql.seedTestUser("gone", UserRoleColumn.MEMBER, canEdit = true, deletedAt = 123L)
                runTest {
                    policy
                        .requireCanEdit(UserId("gone"), UserRole.MEMBER)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("MEMBER with no DB row is denied") {
            withSqlDatabase {
                val policy = UserPermissionPolicy(sql)
                runTest {
                    policy
                        .requireCanEdit(UserId("ghost"), UserRole.MEMBER)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }
    })

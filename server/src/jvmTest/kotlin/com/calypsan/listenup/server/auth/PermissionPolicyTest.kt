@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.SessionId
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

private fun caller(
    id: String,
    role: UserRole,
) = UserPrincipal(UserId(id), SessionId("s-$id"), role)

/**
 * [PermissionPolicy] over a real migrated database: ROOT and ADMIN hold every permission without a
 * lookup; a MEMBER holds exactly the flags on their live row, read fresh on every call.
 */
class PermissionPolicyTest :
    FunSpec({
        test("ROOT and ADMIN pass every permission whatever their flags say") {
            withSqlDatabase {
                val policy = PermissionPolicy(sql)
                sql.seedTestUser("a1", UserRoleColumn.ADMIN, canEdit = false)
                sql.seedTestUser("r1", UserRoleColumn.ROOT, canEdit = false)
                runTest {
                    Permission.known.forEach { permission ->
                        policy.require(caller("a1", UserRole.ADMIN), permission) shouldBe null
                        policy.require(caller("r1", UserRole.ROOT), permission) shouldBe null
                    }
                }
            }
        }

        test("a MEMBER holds exactly the flags on their row") {
            withSqlDatabase {
                val policy = PermissionPolicy(sql)
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true, canCurateLibrary = false)
                sql.seedTestUser("curator", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = true)
                runTest {
                    policy.require(caller("editor", UserRole.MEMBER), Permission.EDIT_METADATA) shouldBe null
                    policy
                        .require(caller("editor", UserRole.MEMBER), Permission.CURATE_LIBRARY)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    policy
                        .require(caller("curator", UserRole.MEMBER), Permission.EDIT_METADATA)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    policy.require(caller("curator", UserRole.MEMBER), Permission.CURATE_LIBRARY) shouldBe null
                }
            }
        }

        test("UNKNOWN is never granted to a MEMBER") {
            withSqlDatabase {
                val policy = PermissionPolicy(sql)
                sql.seedTestUser("m1", UserRoleColumn.MEMBER, canEdit = true, canCurateLibrary = true)
                runTest {
                    policy
                        .require(caller("m1", UserRole.MEMBER), Permission.UNKNOWN)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("a revoked flag is refused on the very next call") {
            withSqlDatabase {
                val policy = PermissionPolicy(sql)
                sql.seedTestUser("m1", UserRoleColumn.MEMBER, canEdit = true, canCurateLibrary = true)
                runTest {
                    policy.require(caller("m1", UserRole.MEMBER), Permission.CURATE_LIBRARY) shouldBe null
                    sql.usersQueries.updatePermissionFlags(can_edit = 1L, can_curate_library = 0L, id = "m1")
                    policy
                        .require(caller("m1", UserRole.MEMBER), Permission.CURATE_LIBRARY)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("a soft-deleted or absent MEMBER holds nothing") {
            withSqlDatabase {
                val policy = PermissionPolicy(sql)
                sql.seedTestUser("gone", UserRoleColumn.MEMBER, canEdit = true, canCurateLibrary = true, deletedAt = 123L)
                runTest {
                    policy
                        .require(caller("gone", UserRole.MEMBER), Permission.EDIT_METADATA)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    policy
                        .require(caller("ghost", UserRole.MEMBER), Permission.EDIT_METADATA)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("requireAdmin passes ROOT and ADMIN and refuses a MEMBER") {
            PermissionPolicy.requireAdmin(caller("r", UserRole.ROOT)) shouldBe null
            PermissionPolicy.requireAdmin(caller("a", UserRole.ADMIN)) shouldBe null
            PermissionPolicy.requireAdmin(caller("m", UserRole.MEMBER)).shouldBeInstanceOf<AuthError.PermissionDenied>()
        }

        test("isAdmin is true for ROOT and ADMIN only") {
            UserRole.entries.associateWith { it.isAdmin() } shouldBe
                mapOf(UserRole.ROOT to true, UserRole.ADMIN to true, UserRole.MEMBER to false)
            UserRoleColumn.entries.associateWith { it.isAdmin() } shouldBe
                mapOf(UserRoleColumn.ROOT to true, UserRoleColumn.ADMIN to true, UserRoleColumn.MEMBER to false)
        }
    })

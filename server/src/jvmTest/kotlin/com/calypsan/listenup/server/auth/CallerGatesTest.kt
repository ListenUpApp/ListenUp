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

private fun providerFor(role: UserRole) = PrincipalProvider { UserPrincipal(UserId("u"), SessionId("s"), role) }

/** The shared caller gates: one answer for a missing caller, whichever service asks. */
class CallerGatesTest :
    FunSpec({
        test("requireAdmin passes an admin and a root caller") {
            providerFor(UserRole.ADMIN).requireAdmin() shouldBe null
            providerFor(UserRole.ROOT).requireAdmin() shouldBe null
        }

        test("requireAdmin denies a member") {
            providerFor(UserRole.MEMBER).requireAdmin().shouldBeInstanceOf<AuthError.PermissionDenied>()
        }

        test("requireAdmin denies a missing caller rather than reporting an expired session") {
            PrincipalProvider.None.requireAdmin().shouldBeInstanceOf<AuthError.PermissionDenied>()
        }

        test("requirePermission denies a missing caller without consulting the database") {
            withSqlDatabase {
                val policy = PermissionPolicy(sql)
                runTest {
                    PrincipalProvider.None
                        .requirePermission(policy, Permission.EDIT_METADATA)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("requirePermission follows the policy for a present caller") {
            withSqlDatabase {
                val policy = PermissionPolicy(sql)
                sql.seedTestUser("u", UserRoleColumn.MEMBER, canEdit = true, canCurateLibrary = false)
                runTest {
                    val provider = providerFor(UserRole.MEMBER)
                    provider.requirePermission(policy, Permission.EDIT_METADATA) shouldBe null
                    provider
                        .requirePermission(policy, Permission.CURATE_LIBRARY)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }
    })

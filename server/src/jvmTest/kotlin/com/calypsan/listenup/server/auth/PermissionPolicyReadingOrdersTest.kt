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

private fun member(id: String) = UserPrincipal(UserId(id), SessionId("s-$id"), UserRole.MEMBER)

/** [Permission.MAKE_READING_ORDERS] (#962): admins hold it implicitly; members hold it by default, until revoked. */
class PermissionPolicyReadingOrdersTest :
    FunSpec({
        test("a live member holds it by default; a revoked or tombstoned one does not") {
            withSqlDatabase {
                sql.seedTestUser("jess")
                sql.seedTestUser("priya", canMakeReadingOrders = false)
                sql.seedTestUser("gone", deletedAt = 5L)
                val policy = PermissionPolicy(sql)
                runTest {
                    policy.require(member("jess"), Permission.MAKE_READING_ORDERS) shouldBe null
                    policy
                        .require(member("priya"), Permission.MAKE_READING_ORDERS)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    policy
                        .require(member("gone"), Permission.MAKE_READING_ORDERS)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("an admin holds it even with the flag off") {
            withSqlDatabase {
                sql.seedTestUser("simon", UserRoleColumn.ADMIN, canMakeReadingOrders = false)
                val policy = PermissionPolicy(sql)
                runTest {
                    policy.require(
                        UserPrincipal(UserId("simon"), SessionId("s"), UserRole.ADMIN),
                        Permission.MAKE_READING_ORDERS,
                    ) shouldBe null
                }
            }
        }

        test("it is independent of edit metadata") {
            withSqlDatabase {
                sql.seedTestUser("jess", canEdit = false, canMakeReadingOrders = true)
                sql.seedTestUser("priya", canEdit = true, canMakeReadingOrders = false)
                val policy = PermissionPolicy(sql)
                runTest {
                    policy.require(member("jess"), Permission.EDIT_METADATA).shouldBeInstanceOf<AuthError.PermissionDenied>()
                    policy.require(member("jess"), Permission.MAKE_READING_ORDERS) shouldBe null
                    policy.require(member("priya"), Permission.EDIT_METADATA) shouldBe null
                    policy
                        .require(member("priya"), Permission.MAKE_READING_ORDERS)
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("a user created without naming the flag has it on — the column defaults to 1") {
            withSqlDatabase {
                sql.seedTestUser("new")
                sql.usersQueries
                    .selectPermissionFlagsLiveById("new")
                    .executeAsOne()
                    .can_make_reading_orders shouldBe 1L
            }
        }
    })

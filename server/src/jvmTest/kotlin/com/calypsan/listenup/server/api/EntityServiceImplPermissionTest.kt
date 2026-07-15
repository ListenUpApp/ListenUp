@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.entity.EntityUpsert
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.EntityRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestSeries
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * `canEdit`-gate tests for [EntityServiceImpl], mirroring [SeriesServiceImplPermissionTest].
 *
 * `upsertEntity` is the representative mutation; `deleteEntity` shares the identical
 * first-statement `requireCanEdit()` guard, so proving the gate fires on one proves the
 * wiring for both. [EntityServiceImpl.listEntitiesForSeries] stays open to any authenticated
 * caller and is covered by [EntityServiceImplTest] instead.
 */
class EntityServiceImplPermissionTest :
    FunSpec({

        test("upsertEntity is denied for a MEMBER without canEdit") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                val service = makeService(this, memberPrincipal("member"))
                runTest {
                    val result =
                        service.upsertEntity(
                            EntityUpsert(id = "vin", kind = EntityKind.CHARACTER, name = "Vin", homeSeriesId = "mistborn"),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("deleteEntity is denied for a MEMBER without canEdit") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true)
                sql.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                val editor = makeService(this, memberPrincipal("editor"))
                val intruder = makeService(this, memberPrincipal("member"))
                runTest {
                    editor
                        .upsertEntity(
                            EntityUpsert(id = "vin", kind = EntityKind.CHARACTER, name = "Vin", homeSeriesId = "mistborn"),
                        )

                    val result = intruder.deleteEntity("vin")

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("upsertEntity succeeds for a granted MEMBER (canEdit=true)") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true)
                val service = makeService(this, memberPrincipal("editor"))
                runTest {
                    val result =
                        service.upsertEntity(
                            EntityUpsert(id = "vin", kind = EntityKind.CHARACTER, name = "Vin", homeSeriesId = "mistborn"),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("upsertEntity succeeds for an ADMIN (implicitly passes)") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val service = makeService(this, rootPrincipal())
                runTest {
                    val result =
                        service.upsertEntity(
                            EntityUpsert(id = "vin", kind = EntityKind.CHARACTER, name = "Vin", homeSeriesId = "mistborn"),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }
    })

private fun makeService(
    dbs: SqlTestDatabases,
    principal: PrincipalProvider,
): EntityServiceImpl =
    EntityServiceImpl(
        entityRepo = EntityRepository(db = dbs.sql, bus = ChangeBus(), registry = SyncRegistry()),
        permissionPolicy = UserPermissionPolicy(dbs.sql),
        principal = principal,
    )

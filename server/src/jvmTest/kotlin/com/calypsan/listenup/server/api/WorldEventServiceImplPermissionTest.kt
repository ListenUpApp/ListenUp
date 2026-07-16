@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.world.EventsBatch
import com.calypsan.listenup.api.dto.world.WorldEventOp
import com.calypsan.listenup.api.dto.world.WorldEventUpsert
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.WorldEventRepository
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
 * `canEdit`-gate tests for [WorldEventServiceImpl], mirroring [EntityServiceImplPermissionTest].
 *
 * `applyBatch` is the single write entry point (both the upsert and delete variants share the
 * identical first-statement `requireCanEdit()` guard), so proving the gate fires on an upsert
 * batch proves the wiring for deletes too. The read methods stay open to any authenticated
 * caller and are covered by [WorldEventServiceImplTest] instead.
 */
class WorldEventServiceImplPermissionTest :
    FunSpec({

        test("applyBatch is denied for a MEMBER without canEdit") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                val service = makeService(this, memberPrincipal("member"))
                runTest {
                    val result =
                        service.applyBatch(
                            EventsBatch(
                                ops =
                                    listOf(
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(id = "e1", homeSeriesId = "mistborn", type = WorldEventType.NOTE, text = "hi"),
                                        ),
                                    ),
                            ),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("applyBatch succeeds for a granted MEMBER (canEdit=true)") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true)
                val service = makeService(this, memberPrincipal("editor"))
                runTest {
                    val result =
                        service.applyBatch(
                            EventsBatch(
                                ops =
                                    listOf(
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(id = "e1", homeSeriesId = "mistborn", type = WorldEventType.NOTE, text = "hi"),
                                        ),
                                    ),
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("applyBatch succeeds for an ADMIN (implicitly passes)") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val service = makeService(this, rootPrincipal())
                runTest {
                    val result =
                        service.applyBatch(
                            EventsBatch(
                                ops =
                                    listOf(
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(id = "e1", homeSeriesId = "mistborn", type = WorldEventType.NOTE, text = "hi"),
                                        ),
                                    ),
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }
    })

private fun makeService(
    dbs: SqlTestDatabases,
    principal: PrincipalProvider,
): WorldEventServiceImpl =
    WorldEventServiceImpl(
        worldEventRepo = WorldEventRepository(db = dbs.sql, bus = ChangeBus(), registry = SyncRegistry()),
        permissionPolicy = UserPermissionPolicy(dbs.sql),
        principal = principal,
    )

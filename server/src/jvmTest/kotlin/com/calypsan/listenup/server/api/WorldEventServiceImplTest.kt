@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.world.EventsBatch
import com.calypsan.listenup.api.dto.world.WorldEventOp
import com.calypsan.listenup.api.dto.world.WorldEventUpsert
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.WorldEventRepository
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestSeries
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Happy-path and shape-validation tests for [WorldEventServiceImpl] — [WorldEventServiceImpl.applyBatch]
 * and the read methods ([WorldEventServiceImpl.listForBook], [WorldEventServiceImpl.listForEntity],
 * [WorldEventServiceImpl.listForWorld]).
 *
 * The `canEdit`-gate deny/allow matrix lives in [WorldEventServiceImplPermissionTest], mirroring
 * [EntityServiceImplPermissionTest]; this file uses a ROOT caller throughout so the gate never
 * blocks the behaviour under test. Uses a real in-memory migrated SQLite database + real
 * [WorldEventRepository]; no mocks.
 */
class WorldEventServiceImplTest :
    FunSpec({

        fun makeService(
            sql: ListenUpDatabase,
            principal: PrincipalProvider = rootPrincipal(),
        ): WorldEventServiceImpl =
            WorldEventServiceImpl(
                worldEventRepo = WorldEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry()),
                permissionPolicy = UserPermissionPolicy(sql),
                principal = principal,
            )

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        // ── happy path ──────────────────────────────────────────────────────────

        test("applyBatch creates a NOTE event and listForBook returns it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hoa1")
                runTest {
                    val service = makeService(sql)
                    val result =
                        service.applyBatch(
                            EventsBatch(
                                ops =
                                    listOf(
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(
                                                id = "e1",
                                                homeBookId = "hoa1",
                                                bookId = "hoa1",
                                                positionMs = 1_000L,
                                                type = WorldEventType.NOTE,
                                                text = "Alcatraz breaks a plate.",
                                            ),
                                        ),
                                    ),
                            ),
                        )
                    result.value()

                    val listed = service.listForBook("hoa1").value()
                    listed shouldHaveSize 1
                    listed.first().id shouldBe "e1"
                }
            }
        }

        test("listForBook is denied without a principal") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, principal = PrincipalProvider.None)
                    val result = service.listForBook("hoa1")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        // ── dual-home validation ────────────────────────────────────────────────

        test("applyBatch rejects an upsert with both homeSeriesId and homeBookId set") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hoa1")
                runTest {
                    val result =
                        makeService(sql).applyBatch(
                            EventsBatch(
                                ops =
                                    listOf(
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(
                                                id = "e1",
                                                homeSeriesId = "mistborn",
                                                homeBookId = "hoa1",
                                                type = WorldEventType.NOTE,
                                                text = "invalid",
                                            ),
                                        ),
                                    ),
                            ),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        test("applyBatch rejects an upsert with neither homeSeriesId nor homeBookId set") {
            withSqlDatabase {
                runTest {
                    val result =
                        makeService(sql).applyBatch(
                            EventsBatch(
                                ops =
                                    listOf(
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(id = "e1", type = WorldEventType.NOTE, text = "invalid"),
                                        ),
                                    ),
                            ),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        // ── anchor pairing ──────────────────────────────────────────────────────

        test("applyBatch rejects an upsert with bookId set but positionMs unset") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hoa1")
                runTest {
                    val result =
                        makeService(sql).applyBatch(
                            EventsBatch(
                                ops =
                                    listOf(
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(
                                                id = "e1",
                                                homeSeriesId = "mistborn",
                                                bookId = "hoa1",
                                                type = WorldEventType.NOTE,
                                                text = "invalid anchor",
                                            ),
                                        ),
                                    ),
                            ),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        // ── per-type validation ─────────────────────────────────────────────────

        test("applyBatch rejects a NOTE event with blank text") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                runTest {
                    val result =
                        makeService(sql).applyBatch(
                            EventsBatch(
                                ops =
                                    listOf(
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(
                                                id = "e1",
                                                homeSeriesId = "mistborn",
                                                type = WorldEventType.NOTE,
                                                text = "   ",
                                            ),
                                        ),
                                    ),
                            ),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        test("applyBatch rejects a MOVES_TO event with no objectEntityId") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                runTest {
                    val result =
                        makeService(sql).applyBatch(
                            EventsBatch(
                                ops =
                                    listOf(
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(
                                                id = "e1",
                                                homeSeriesId = "mistborn",
                                                type = WorldEventType.MOVES_TO,
                                                text = "",
                                                subjectEntityId = "vin",
                                            ),
                                        ),
                                    ),
                            ),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        test("applyBatch accepts a DEPARTS event with blank text and no object") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                runTest {
                    val result =
                        makeService(sql).applyBatch(
                            EventsBatch(
                                ops =
                                    listOf(
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(
                                                id = "e1",
                                                homeSeriesId = "mistborn",
                                                type = WorldEventType.DEPARTS,
                                                text = "",
                                            ),
                                        ),
                                    ),
                            ),
                        )

                    result.value()
                }
            }
        }

        test("applyBatch fails the whole batch, applying nothing, when one op is invalid") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                runTest {
                    val service = makeService(sql)
                    val result =
                        service.applyBatch(
                            EventsBatch(
                                ops =
                                    listOf(
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(id = "e1", homeSeriesId = "mistborn", type = WorldEventType.NOTE, text = "ok"),
                                        ),
                                        WorldEventOp.Upsert(
                                            WorldEventUpsert(id = "e2", homeSeriesId = "mistborn", type = WorldEventType.NOTE, text = "  "),
                                        ),
                                    ),
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    service.listForWorld(homeSeriesId = "mistborn", homeBookId = null).value() shouldHaveSize 0
                }
            }
        }

        // ── listForWorld ────────────────────────────────────────────────────────

        test("listForWorld rejects neither homeSeriesId nor homeBookId set") {
            withSqlDatabase {
                runTest {
                    val result = makeService(sql).listForWorld(homeSeriesId = null, homeBookId = null)
                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }
    })

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.entity.EntityUpsert
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.EntityRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestSeries
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Happy-path tests for [EntityServiceImpl] — create/update via [EntityServiceImpl.upsertEntity],
 * [EntityServiceImpl.listEntitiesForSeries], [EntityServiceImpl.listEntitiesForBook], and
 * [EntityServiceImpl.deleteEntity].
 *
 * The `canEdit`-gate deny/allow matrix lives in [EntityServiceImplPermissionTest], mirroring
 * [SeriesServiceImplPermissionTest]; this file uses a ROOT caller throughout so the gate never
 * blocks the behaviour under test. Uses a real in-memory migrated SQLite database + real
 * [EntityRepository]; no mocks.
 */
class EntityServiceImplTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun makeService(
            sql: ListenUpDatabase,
            principal: PrincipalProvider = rootPrincipal(),
        ): EntityServiceImpl =
            EntityServiceImpl(
                entityRepo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry()),
                permissionPolicy = UserPermissionPolicy(sql),
                principal = principal,
                clock = fixedClock,
            )

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        test("upsertEntity creates a new entity and listEntitiesForSeries returns it") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                runTest {
                    val service = makeService(sql)
                    val created =
                        service
                            .upsertEntity(
                                EntityUpsert(
                                    id = "vin",
                                    kind = EntityKind.CHARACTER,
                                    name = "Vin",
                                    homeSeriesId = "mistborn",
                                ),
                            ).value()
                    created.name shouldBe "Vin"
                    created.kind shouldBe EntityKind.CHARACTER

                    val listed = service.listEntitiesForSeries("mistborn").value()
                    listed shouldHaveSize 1
                    listed.first().id shouldBe "vin"
                }
            }
        }

        test("upsertEntity on an existing id updates it in place, preserving createdAt") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                runTest {
                    val service = makeService(sql)
                    val created =
                        service
                            .upsertEntity(
                                EntityUpsert(id = "vin", kind = EntityKind.CHARACTER, name = "Vin", homeSeriesId = "mistborn"),
                            ).value()

                    val updated =
                        service
                            .upsertEntity(
                                EntityUpsert(
                                    id = "vin",
                                    kind = EntityKind.CHARACTER,
                                    name = "Valette Renoux",
                                    homeSeriesId = "mistborn",
                                ),
                            ).value()

                    updated.name shouldBe "Valette Renoux"
                    updated.createdAt shouldBe created.createdAt

                    service.listEntitiesForSeries("mistborn").value() shouldHaveSize 1
                }
            }
        }

        test("upsertEntity creates a book-homed entity and listEntitiesForBook returns it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hoa1")
                runTest {
                    val service = makeService(sql)
                    val created =
                        service
                            .upsertEntity(
                                EntityUpsert(
                                    id = "alcatraz",
                                    kind = EntityKind.CHARACTER,
                                    name = "Alcatraz",
                                    homeBookId = "hoa1",
                                ),
                            ).value()
                    created.homeBookId shouldBe "hoa1"
                    created.homeSeriesId shouldBe null

                    val listed = service.listEntitiesForBook("hoa1").value()
                    listed shouldHaveSize 1
                    listed.first().id shouldBe "alcatraz"
                }
            }
        }

        test("listEntitiesForBook excludes series-homed entities") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hoa1")
                runTest {
                    val service = makeService(sql)
                    service
                        .upsertEntity(
                            EntityUpsert(id = "vin", kind = EntityKind.CHARACTER, name = "Vin", homeSeriesId = "mistborn"),
                        ).value()
                    service
                        .upsertEntity(
                            EntityUpsert(id = "alcatraz", kind = EntityKind.CHARACTER, name = "Alcatraz", homeBookId = "hoa1"),
                        ).value()

                    service.listEntitiesForBook("hoa1").value() shouldHaveSize 1
                }
            }
        }

        test("upsertEntity rejects both homeSeriesId and homeBookId set") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hoa1")
                runTest {
                    val result =
                        makeService(sql).upsertEntity(
                            EntityUpsert(
                                id = "vin",
                                kind = EntityKind.CHARACTER,
                                name = "Vin",
                                homeSeriesId = "mistborn",
                                homeBookId = "hoa1",
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        test("upsertEntity rejects neither homeSeriesId nor homeBookId set") {
            withSqlDatabase {
                runTest {
                    val result =
                        makeService(sql).upsertEntity(
                            EntityUpsert(id = "vin", kind = EntityKind.CHARACTER, name = "Vin"),
                        )

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        test("deleteEntity removes it from listEntitiesForSeries") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                runTest {
                    val service = makeService(sql)
                    service
                        .upsertEntity(
                            EntityUpsert(id = "vin", kind = EntityKind.CHARACTER, name = "Vin", homeSeriesId = "mistborn"),
                        ).value()

                    service.deleteEntity("vin").value()

                    service.listEntitiesForSeries("mistborn").value().shouldBeEmpty()
                }
            }
        }

        test("deleteEntity on a non-existent id fails with SyncError.NotFound") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                runTest {
                    val result = makeService(sql).deleteEntity("ghost")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }

        test("listEntitiesForSeries is denied without a principal") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                runTest {
                    val service = makeService(sql, principal = PrincipalProvider.None)
                    val result = service.listEntitiesForSeries("mistborn")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("listEntitiesForSeries excludes entities from a different series") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestSeries("stormlight")
                runTest {
                    val service = makeService(sql)
                    service
                        .upsertEntity(
                            EntityUpsert(id = "vin", kind = EntityKind.CHARACTER, name = "Vin", homeSeriesId = "mistborn"),
                        ).value()
                    service
                        .upsertEntity(
                            EntityUpsert(
                                id = "kaladin",
                                kind = EntityKind.CHARACTER,
                                name = "Kaladin",
                                homeSeriesId = "stormlight",
                            ),
                        ).value()

                    val mistbornEntities = service.listEntitiesForSeries("mistborn").value()
                    mistbornEntities shouldHaveSize 1
                    mistbornEntities.first().id shouldBe "vin"
                }
            }
        }

        test("upsertEntity round-trips an entity of kind LOCATION") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                runTest {
                    val created =
                        makeService(sql)
                            .upsertEntity(
                                EntityUpsert(
                                    id = "luthadel",
                                    kind = EntityKind.LOCATION,
                                    name = "Luthadel",
                                    homeSeriesId = "mistborn",
                                ),
                            ).value()
                    created.kind shouldBe EntityKind.LOCATION
                }
            }
        }
    })

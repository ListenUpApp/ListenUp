@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.server.sync.ReadingOrderRepository
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.shouldBeDeniedPermission
import com.calypsan.listenup.server.testing.shouldPassThePermissionGate
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * canEdit-gate tests for [SeriesServiceImpl] (closes MA holistic-review finding I1).
 *
 * `updateSeries` is the representative edit, gated on `Permission.EDIT_METADATA`; merge, merge
 * history, undo and delete are gated on `Permission.CURATE_LIBRARY`, and the matrix test covers
 * each. Reads stay open and are covered by the existing [SeriesServiceImplTest].
 */
class SeriesServiceImplPermissionTest :
    FunSpec({

        test("updateSeries is denied for a MEMBER without canEdit") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                val deps = makeService(this)
                runTest {
                    val seriesId = deps.seriesRepo.resolveOrCreate("Mistborn")
                    val service = deps.service.copyWith(memberPrincipal("member"))

                    val result = service.updateSeries(seriesId, seriesNameUpdate("Renamed"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("updateSeries succeeds for a granted MEMBER (canEdit=true)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true)
                val deps = makeService(this)
                runTest {
                    val seriesId = deps.seriesRepo.resolveOrCreate("Mistborn")
                    val service = deps.service.copyWith(memberPrincipal("editor"))

                    val result = service.updateSeries(seriesId, seriesNameUpdate("Renamed"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("updateSeries succeeds for an ADMIN (implicitly passes)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeService(this)
                runTest {
                    val seriesId = deps.seriesRepo.resolveOrCreate("Mistborn")
                    val service = deps.service.copyWith(rootPrincipal())

                    val result = service.updateSeries(seriesId, seriesNameUpdate("Renamed"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("series merge, merge history, undo and delete need Curate library, not Edit metadata") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true, canCurateLibrary = false)
                sql.seedTestUser("curator", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = true)
                sql.seedTestUser("nobody", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = false)
                val deps = makeService(this)
                runTest {
                    val editor = deps.service.copyWith(memberPrincipal("editor"))
                    val curator = deps.service.copyWith(memberPrincipal("curator"))
                    val nobody = deps.service.copyWith(memberPrincipal("nobody"))
                    val admin = deps.service.copyWith(rootPrincipal())
                    val a = SeriesId("s-a")
                    val b = SeriesId("s-b")
                    val receipt = MergeReceiptId("r-1")

                    for (refused in listOf(editor, nobody)) {
                        refused.mergeSeries(a, b).shouldBeDeniedPermission()
                        refused.listMergeReceipts(b).shouldBeDeniedPermission()
                        refused.undoSeriesMerge(receipt).shouldBeDeniedPermission()
                        refused.deleteSeries(a).shouldBeDeniedPermission()
                    }
                    for (allowed in listOf(curator, admin)) {
                        allowed.mergeSeries(a, b).shouldPassThePermissionGate()
                        allowed.listMergeReceipts(b).shouldPassThePermissionGate()
                        allowed.undoSeriesMerge(receipt).shouldPassThePermissionGate()
                        allowed.deleteSeries(a).shouldPassThePermissionGate()
                    }
                }
            }
        }

        test("renaming a series still needs Edit metadata") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("curator", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = true)
                val deps = makeService(this)
                runTest {
                    val seriesId = deps.seriesRepo.resolveOrCreate("Mistborn")
                    deps.service
                        .copyWith(memberPrincipal("curator"))
                        .updateSeries(seriesId, seriesNameUpdate("Renamed"))
                        .shouldBeDeniedPermission()
                }
            }
        }
    })

private data class PermServiceDeps(
    val service: SeriesServiceImpl,
    val seriesRepo: SeriesRepository,
)

private fun makeService(dbs: SqlTestDatabases): PermServiceDeps {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val contributorRepo = ContributorRepository(db = dbs.sql, bus = bus, registry = registry)
    val seriesRepo = SeriesRepository(db = dbs.sql, bus = bus, registry = registry)
    val bookRepo =
        BookRepository(
            db = dbs.sql,
            driver = dbs.driver,
            bus = bus,
            registry = registry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(db = dbs.sql, bus = bus, registry = registry),
        )
    val service =
        SeriesServiceImpl(
            seriesRepo = seriesRepo,
            bookRepo = bookRepo,
            sqlDb = dbs.sql,
            accessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
            readingOrders = ReadingOrderRepository(dbs.sql, ChangeBus(), SyncRegistry()),
            permissionPolicy = PermissionPolicy(dbs.sql),
        )
    return PermServiceDeps(service, seriesRepo)
}

private fun seriesNameUpdate(name: String) =
    com.calypsan.listenup.api.dto
        .SeriesUpdate(name = name)

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.EntityRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.WorldEventRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * canEdit-gate tests for [SeriesServiceImpl] (closes MA holistic-review finding I1).
 *
 * `updateSeries` is the representative mutation; every series mutation
 * (`updateSeries`/`deleteSeries`/`mergeSeries`) shares the identical first-statement
 * `requireCanEdit()` guard, so proving the gate fires on one proves the wiring. Reads stay
 * open and are covered by the existing [SeriesServiceImplTest].
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
    val tagRepo = TagRepository(db = dbs.sql, bus = bus, registry = registry)
    val bookTagRepo = BookTagRepository(db = dbs.sql, bus = bus, registry = registry)
    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, dbs.sql, dbs.driver)
    val service =
        SeriesServiceImpl(
            seriesRepo = seriesRepo,
            bookRepo = bookRepo,
            entityRepo = EntityRepository(dbs.sql, bus, registry),
            worldEventRepo = WorldEventRepository(dbs.sql, bus, registry),
            reindexer = reindexer,
            sqlDb = dbs.sql,
            accessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
            permissionPolicy = UserPermissionPolicy(dbs.sql),
        )
    return PermServiceDeps(service, seriesRepo)
}

private fun seriesNameUpdate(name: String) =
    com.calypsan.listenup.api.dto
        .SeriesUpdate(name = name)

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.ContributorUpdate
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
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
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
 * canEdit-gate tests for [ContributorServiceImpl] (closes MA holistic-review finding I1).
 *
 * `updateContributor` is the representative mutation; every contributor mutation
 * (`updateContributor`/`deleteContributor`/`mergeContributors`/`unmergeContributor`) shares
 * the identical first-statement `requireCanEdit()` guard. Reads stay open and are covered by
 * the existing [ContributorServiceImplTest].
 */
class ContributorServiceImplPermissionTest :
    FunSpec({

        test("updateContributor is denied for a MEMBER without canEdit") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                db.sql.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                val deps = makeContributorPermService(db)
                runTest {
                    val id = deps.contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    val service = deps.service.copyWith(memberPrincipal("member"))

                    val result = service.updateContributor(id, ContributorUpdate(name = "Renamed"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("updateContributor succeeds for a granted MEMBER (canEdit=true)") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                db.sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true)
                val deps = makeContributorPermService(db)
                runTest {
                    val id = deps.contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    val service = deps.service.copyWith(memberPrincipal("editor"))

                    val result = service.updateContributor(id, ContributorUpdate(name = "Renamed"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("updateContributor succeeds for an ADMIN (implicitly passes)") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeContributorPermService(db)
                runTest {
                    val id = deps.contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    val service = deps.service.copyWith(rootPrincipal())

                    val result = service.updateContributor(id, ContributorUpdate(name = "Renamed"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }
    })

private data class ContributorPermDeps(
    val service: ContributorServiceImpl,
    val contributorRepo: ContributorRepository,
)

private fun makeContributorPermService(db: SqlTestDatabases): ContributorPermDeps {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val contributorRepo = ContributorRepository(db = db.sql, bus = bus, registry = registry)
    val seriesRepo = SeriesRepository(db = db.sql, bus = bus, registry = registry)
    val bookRepo =
        BookRepository(
            db = db.sql,
            driver = db.driver,
            bus = bus,
            registry = registry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(db.sql, bus, registry),
        )
    val tagRepo = TagRepository(db = db.sql, bus = bus, registry = registry)
    val bookTagRepo = BookTagRepository(db = db.sql, bus = bus, registry = registry)
    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db.sql, db.driver)
    val service =
        ContributorServiceImpl(
            contributorRepo = contributorRepo,
            bookRepo = bookRepo,
            reindexer = reindexer,
            sqlDb = db.sql,
            accessPolicy = BookAccessPolicy(db.sql, db.driver),
            permissionPolicy = UserPermissionPolicy(db.sql),
        )
    return ContributorPermDeps(service, contributorRepo)
}

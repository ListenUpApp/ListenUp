@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
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
 * canEdit-gate tests for [ContributorServiceImpl] (closes MA holistic-review finding I1).
 *
 * `updateContributor` is the representative edit, gated on `Permission.EDIT_METADATA`; merge,
 * unmerge and delete are gated on `Permission.CURATE_LIBRARY`, and the matrix test covers each.
 * Reads stay open and are covered by the existing [ContributorServiceImplTest].
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

        test("contributor merge, unmerge and delete need Curate library, not Edit metadata") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true, canCurateLibrary = false)
                sql.seedTestUser("curator", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = true)
                sql.seedTestUser("nobody", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = false)
                val deps = makeContributorPermService(db)
                runTest {
                    val editor = deps.service.copyWith(memberPrincipal("editor"))
                    val curator = deps.service.copyWith(memberPrincipal("curator"))
                    val nobody = deps.service.copyWith(memberPrincipal("nobody"))
                    val admin = deps.service.copyWith(rootPrincipal())
                    val a = ContributorId("c-a")
                    val b = ContributorId("c-b")

                    editor.mergeContributors(a, b).shouldBeDeniedPermission()
                    editor.unmergeContributor(a, "Alias").shouldBeDeniedPermission()
                    editor.deleteContributor(a).shouldBeDeniedPermission()
                    nobody.mergeContributors(a, b).shouldBeDeniedPermission()
                    nobody.unmergeContributor(a, "Alias").shouldBeDeniedPermission()
                    nobody.deleteContributor(a).shouldBeDeniedPermission()

                    curator.mergeContributors(a, b).shouldPassThePermissionGate()
                    curator.unmergeContributor(a, "Alias").shouldPassThePermissionGate()
                    curator.deleteContributor(a).shouldPassThePermissionGate()
                    admin.mergeContributors(a, b).shouldPassThePermissionGate()
                    admin.unmergeContributor(a, "Alias").shouldPassThePermissionGate()
                    admin.deleteContributor(a).shouldPassThePermissionGate()
                }
            }
        }

        test("editing a contributor still needs Edit metadata, which Curate library alone does not grant") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("curator", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = true)
                val deps = makeContributorPermService(db)
                runTest {
                    val id = deps.contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    deps.service
                        .copyWith(memberPrincipal("curator"))
                        .updateContributor(id, ContributorUpdate(name = "Renamed"))
                        .shouldBeDeniedPermission()
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
    val service =
        ContributorServiceImpl(
            contributorRepo = contributorRepo,
            bookRepo = bookRepo,
            sqlDb = db.sql,
            accessPolicy = BookAccessPolicy(db.sql, db.driver),
            permissionPolicy = PermissionPolicy(db.sql),
        )
    return ContributorPermDeps(service, contributorRepo)
}

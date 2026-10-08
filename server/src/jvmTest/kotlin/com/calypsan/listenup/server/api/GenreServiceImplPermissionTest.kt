@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
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
 * canEdit-gate tests for [GenreServiceImpl] (closes MA holistic-review finding I1).
 *
 * `createGenre` is the representative edit, gated on `Permission.EDIT_METADATA`; merge, merge
 * history, undo and delete are gated on `Permission.CURATE_LIBRARY`, and the matrix test covers
 * each. Reads stay open and are covered by the existing genre read tests.
 */
class GenreServiceImplPermissionTest :
    FunSpec({

        test("createGenre is denied for a MEMBER without canEdit") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                val service = makeGenrePermService(sql, driver).copyWith(memberPrincipal("member"))
                runTest {
                    val result = service.createGenre(parentId = null, name = "Fiction")

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("createGenre succeeds for a granted MEMBER (canEdit=true)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true)
                val service = makeGenrePermService(sql, driver).copyWith(memberPrincipal("editor"))
                runTest {
                    val result = service.createGenre(parentId = null, name = "Fiction")

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("createGenre succeeds for an ADMIN (implicitly passes)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val service = makeGenrePermService(sql, driver).copyWith(rootPrincipal())
                runTest {
                    val result = service.createGenre(parentId = null, name = "Fiction")

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("genre merge, merge history, undo and delete need Curate library, not Edit metadata") {
            withSqlDatabase {
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true, canCurateLibrary = false)
                sql.seedTestUser("curator", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = true)
                sql.seedTestUser("nobody", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = false)
                runTest {
                    val service = makeGenrePermService(sql, driver)
                    val editor = service.copyWith(memberPrincipal("editor"))
                    val curator = service.copyWith(memberPrincipal("curator"))
                    val nobody = service.copyWith(memberPrincipal("nobody"))
                    val admin = service.copyWith(rootPrincipal())
                    val a = GenreId("g-a")
                    val b = GenreId("g-b")
                    val receipt = MergeReceiptId("r-1")

                    for (refused in listOf(editor, nobody)) {
                        refused.mergeGenres(a, b).shouldBeDeniedPermission()
                        refused.listMergeReceipts(b).shouldBeDeniedPermission()
                        refused.undoGenreMerge(receipt).shouldBeDeniedPermission()
                        refused.deleteGenre(a).shouldBeDeniedPermission()
                    }
                    for (allowed in listOf(curator, admin)) {
                        allowed.mergeGenres(a, b).shouldPassThePermissionGate()
                        allowed.listMergeReceipts(b).shouldPassThePermissionGate()
                        allowed.undoGenreMerge(receipt).shouldPassThePermissionGate()
                        allowed.deleteGenre(a).shouldPassThePermissionGate()
                    }
                }
            }
        }

        test("creating a genre still needs Edit metadata, which Curate library alone does not grant") {
            withSqlDatabase {
                sql.seedTestUser("curator", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = true)
                runTest {
                    makeGenrePermService(sql, driver)
                        .copyWith(memberPrincipal("curator"))
                        .createGenre(parentId = null, name = "Fiction")
                        .shouldBeDeniedPermission()
                }
            }
        }
    })

private fun makeGenrePermService(
    sql: ListenUpDatabase,
    driver: SqlDriver,
): GenreServiceImpl {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val contributorRepo = ContributorRepository(db = sql, bus = bus, registry = registry)
    val seriesRepo = SeriesRepository(db = sql, bus = bus, registry = registry)
    val genreRepo = GenreRepository(db = sql, bus = bus, registry = registry)
    val bookRepo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = registry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = genreRepo,
        )
    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry, driver = driver)
    return GenreServiceImpl(
        genreRepository = genreRepo,
        bookRepository = bookRepo,
        sqlDb = sql,
        accessPolicy = BookAccessPolicy(sql, driver),
        permissionPolicy = PermissionPolicy(sql),
    )
}

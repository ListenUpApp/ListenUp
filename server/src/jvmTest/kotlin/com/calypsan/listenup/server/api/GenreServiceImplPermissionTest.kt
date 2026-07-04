@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * canEdit-gate tests for [GenreServiceImpl] (closes MA holistic-review finding I1).
 *
 * `createGenre` is the representative mutation; every genre mutation
 * (`createGenre`/`updateGenre`/`deleteGenre`/`moveGenre`/`mergeGenres`/`mapUnmappedToGenre`)
 * shares the identical first-statement `requireCanEdit()` guard. Reads stay open and are
 * covered by the existing genre read tests.
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
    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, sql, driver)
    return GenreServiceImpl(
        genreRepository = genreRepo,
        bookRepository = bookRepo,
        reindexer = reindexer,
        sqlDb = sql,
        accessPolicy = BookAccessPolicy(sql, driver),
        permissionPolicy = UserPermissionPolicy(sql),
    )
}

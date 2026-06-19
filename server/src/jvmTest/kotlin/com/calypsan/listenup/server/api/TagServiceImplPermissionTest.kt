@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * canEdit-gate tests for [TagServiceImpl] (closes MA holistic-review finding I1).
 *
 * `addTagToBook` is the representative mutation; every tag mutation
 * (`addTagToBook`/`removeTagFromBook`/`renameTag`/`deleteTag`) shares the identical
 * first-statement `requireCanEdit()` guard. Reads stay open and are covered by the existing
 * [TagServiceImplTest].
 */
class TagServiceImplPermissionTest :
    FunSpec({

        test("addTagToBook is denied for a MEMBER without canEdit") {
            withSqlDatabase {
                exposed.seedTestLibraryAndFolder()
                exposed.seedTestBook("book1")
                exposed.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                val service = makeTagPermService(this).copyWith(memberPrincipal("member"))
                runTest {
                    val result = service.addTagToBook(BookId("book1"), "Sci-Fi")

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("addTagToBook succeeds for a granted MEMBER (canEdit=true)") {
            withSqlDatabase {
                exposed.seedTestLibraryAndFolder()
                exposed.seedTestBook("book1")
                exposed.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true)
                val service = makeTagPermService(this).copyWith(memberPrincipal("editor"))
                runTest {
                    val result = service.addTagToBook(BookId("book1"), "Sci-Fi")

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("addTagToBook succeeds for an ADMIN (implicitly passes)") {
            withSqlDatabase {
                exposed.seedTestLibraryAndFolder()
                exposed.seedTestBook("book1")
                val service = makeTagPermService(this).copyWith(rootPrincipal())
                runTest {
                    val result = service.addTagToBook(BookId("book1"), "Sci-Fi")

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }
    })

private fun makeTagPermService(dbs: SqlTestDatabases): TagServiceImpl {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val tagRepo = TagRepository(db = dbs.sql, bus = bus, registry = registry)
    val bookTagRepo = BookTagRepository(db = dbs.sql, bus = bus, registry = registry)
    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, dbs.exposed)
    return TagServiceImpl(
        tagRepository = tagRepo,
        bookTagRepository = bookTagRepo,
        reindexer = reindexer,
        db = dbs.exposed,
        permissionPolicy = UserPermissionPolicy(dbs.exposed),
    )
}

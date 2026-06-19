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
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                db.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                val service = makeTagPermService(db).copyWith(memberPrincipal("member"))
                runTest {
                    val result = service.addTagToBook(BookId("book1"), "Sci-Fi")

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("addTagToBook succeeds for a granted MEMBER (canEdit=true)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                db.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true)
                val service = makeTagPermService(db).copyWith(memberPrincipal("editor"))
                runTest {
                    val result = service.addTagToBook(BookId("book1"), "Sci-Fi")

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("addTagToBook succeeds for an ADMIN (implicitly passes)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                val service = makeTagPermService(db).copyWith(rootPrincipal())
                runTest {
                    val result = service.addTagToBook(BookId("book1"), "Sci-Fi")

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }
    })

private fun makeTagPermService(db: Database): TagServiceImpl {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val tagRepo = TagRepository(db = db, bus = bus, registry = registry)
    val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = registry)
    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db)
    return TagServiceImpl(
        tagRepository = tagRepo,
        bookTagRepository = bookTagRepo,
        reindexer = reindexer,
        db = db,
        permissionPolicy = UserPermissionPolicy(db),
    )
}

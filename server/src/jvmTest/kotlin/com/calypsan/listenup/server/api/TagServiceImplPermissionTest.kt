@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.TagId
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
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
import com.calypsan.listenup.server.testing.shouldBeDeniedPermission
import com.calypsan.listenup.server.testing.shouldPassThePermissionGate
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * canEdit-gate tests for [TagServiceImpl] (closes MA holistic-review finding I1).
 *
 * `addTagToBook` is the representative edit, gated on `Permission.EDIT_METADATA`; `deleteTag` is
 * gated on `Permission.CURATE_LIBRARY`. Reads stay open and are covered by the existing
 * [TagServiceImplTest].
 */
class TagServiceImplPermissionTest :
    FunSpec({

        test("addTagToBook is denied for a MEMBER without canEdit") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
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
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true)
                val service = makeTagPermService(this).copyWith(memberPrincipal("editor"))
                runTest {
                    val result = service.addTagToBook(BookId("book1"), "Sci-Fi")

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("addTagToBook succeeds for an ADMIN (implicitly passes)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val service = makeTagPermService(this).copyWith(rootPrincipal())
                runTest {
                    val result = service.addTagToBook(BookId("book1"), "Sci-Fi")

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("deleting a tag needs Curate library; renaming it needs Edit metadata") {
            withSqlDatabase {
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true, canCurateLibrary = false)
                sql.seedTestUser("curator", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = true)
                sql.seedTestUser("nobody", UserRoleColumn.MEMBER, canEdit = false, canCurateLibrary = false)
                runTest {
                    val service = makeTagPermService(this@withSqlDatabase)
                    val tag = TagId("t-1")

                    service.copyWith(memberPrincipal("editor")).deleteTag(tag).shouldBeDeniedPermission()
                    service.copyWith(memberPrincipal("nobody")).deleteTag(tag).shouldBeDeniedPermission()
                    service.copyWith(memberPrincipal("curator")).deleteTag(tag).shouldPassThePermissionGate()
                    service.copyWith(rootPrincipal()).deleteTag(tag).shouldPassThePermissionGate()
                    service.copyWith(memberPrincipal("curator")).renameTag(tag, "Renamed").shouldBeDeniedPermission()
                }
            }
        }
    })

private fun makeTagPermService(dbs: SqlTestDatabases): TagServiceImpl {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val tagRepo = TagRepository(db = dbs.sql, bus = bus, registry = registry)
    val bookTagRepo = BookTagRepository(db = dbs.sql, bus = bus, registry = registry, driver = dbs.driver)
    return TagServiceImpl(
        tagRepository = tagRepo,
        bookTagRepository = bookTagRepo,
        sql = dbs.sql,
        accessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
        permissionPolicy = PermissionPolicy(dbs.sql),
    )
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.makeBooksVisibleTo
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Edit-gate tests for [MoodServiceImpl]'s book-mood writes, the twin of [TagServiceImplPermissionTest].
 *
 * `addMoodToBook` / `removeMoodFromBook` need `Permission.EDIT_METADATA` **and** a book the caller can
 * see; a book they cannot see answers exactly as an absent one does, so the reply never confirms the id.
 */
class MoodServiceImplPermissionTest :
    FunSpec({

        test("addMoodToBook is denied for a MEMBER without canEdit") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                val service = makeMoodPermService(this).copyWith(memberPrincipal("member"))
                runTest {
                    makeBooksVisibleTo("member", "book1")

                    val result = service.addMoodToBook(BookId("book1"), "Cozy")

                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("addMoodToBook on a book the editor cannot see answers BookNotFound and writes nothing") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("visible")
                sql.seedTestBook("hidden")
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true)
                val service = makeMoodPermService(this).copyWith(memberPrincipal("editor"))
                runTest {
                    makeBooksVisibleTo("editor", "visible")

                    // Control: the visible book takes the mood, so a blanket deny would fail here.
                    service.addMoodToBook(BookId("visible"), "Cozy").shouldBeInstanceOf<AppResult.Success<*>>()

                    val denied = service.addMoodToBook(BookId("hidden"), "Cozy")

                    denied.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<MoodError.BookNotFound>()
                    bookMoodRepository(this@withSqlDatabase).findAllForBook("hidden").shouldBeEmpty()
                }
            }
        }

        test("removeMoodFromBook on a book the editor cannot see answers BookNotFound and keeps the mood") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hidden")
                sql.seedTestUser("editor", UserRoleColumn.MEMBER, canEdit = true)
                val base = makeMoodPermService(this)
                runTest {
                    val mood =
                        base
                            .copyWith(rootPrincipal())
                            .addMoodToBook(BookId("hidden"), "Cozy")
                            .shouldBeInstanceOf<AppResult.Success<Mood>>()
                            .data

                    val denied =
                        base.copyWith(memberPrincipal("editor")).removeMoodFromBook(BookId("hidden"), MoodId(mood.id))

                    denied.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<MoodError.BookNotFound>()
                    bookMoodRepository(this@withSqlDatabase).findAllForBook("hidden").map { it.moodId } shouldBe
                        listOf(mood.id)
                }
            }
        }

        test("addMoodToBook succeeds for an ADMIN on a book in no collection") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val service = makeMoodPermService(this).copyWith(rootPrincipal())
                runTest {
                    service.addMoodToBook(BookId("book1"), "Cozy").shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }
    })

private fun bookMoodRepository(dbs: SqlTestDatabases): BookMoodRepository =
    BookMoodRepository(db = dbs.sql, bus = ChangeBus(), registry = SyncRegistry(), driver = dbs.driver)

private fun makeMoodPermService(dbs: SqlTestDatabases): MoodServiceImpl {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    return MoodServiceImpl(
        moodRepository = MoodRepository(db = dbs.sql, bus = bus, registry = registry),
        bookMoodRepository = BookMoodRepository(db = dbs.sql, bus = bus, registry = registry, driver = dbs.driver),
        sql = dbs.sql,
        accessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
        permissionPolicy = PermissionPolicy(dbs.sql),
    )
}

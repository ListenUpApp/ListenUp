@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.BookRevisionTouch
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FakeBookRevisionTouch
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlin.time.Instant
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Pins the revision-bump side of collection-membership mutation: adding a book to a collection
 * must bump that book's sync revision (via [BookRevisionTouch]) so each member's incremental
 * `revision > cursor` pull re-delivers the now-visible book. Asserted against a recording fake
 * rather than the real [com.calypsan.listenup.server.services.BookRepository] — the touch is the
 * contract under test, not the revision-column mechanics.
 */
class CollectionMembershipRevisionTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        fun makeCollectionService(
            db: SqlTestDatabases,
            bookRevisionTouch: BookRevisionTouch = FakeBookRevisionTouch(),
        ): CollectionServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo =
                CollectionRepository(
                    db = db.sql,
                    bus = bus,
                    registry = registry,
                    driver = db.driver,
                )
            val collectionBookRepo =
                CollectionBookRepository(
                    db = db.sql,
                    bus = bus,
                    registry = registry,
                    driver = db.driver,
                )
            val grantRepo =
                CollectionGrantRepository(
                    db = db.sql,
                    bus = bus,
                    registry = registry,
                    driver = db.driver,
                )
            val accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo)
            return CollectionServiceImpl(
                collectionRepo = collectionRepo,
                collectionBookRepo = collectionBookRepo,
                grantRepo = grantRepo,
                accessPolicy = accessPolicy,
                permissionPolicy = UserPermissionPolicy(db.sql),
                bus = bus,
                sql = db.sql,
                clock = fixedClock,
                bookRevisionTouch = bookRevisionTouch,
                principal = principalFor("u1"),
            )
        }

        fun CollectionServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): CollectionServiceImpl = copyWith(principalFor(userId, role))

        test("addBookToCollection touches the added book's revision") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestBook(bookId = "b1")
                runTest(UnconfinedTestDispatcher()) {
                    val touch = FakeBookRevisionTouch()
                    val service = makeCollectionService(db, bookRevisionTouch = touch)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shelf")
                    require(created is AppResult.Success)

                    owner.addBookToCollection(created.data.id, BookId("b1")).let {
                        require(it is AppResult.Success)
                    }

                    touch.touched shouldContainExactly listOf("b1")
                }
            }
        }

        test("removeBookFromCollection touches the removed book's revision") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestBook(bookId = "b1")
                runTest(UnconfinedTestDispatcher()) {
                    val touch = FakeBookRevisionTouch()
                    val service = makeCollectionService(db, bookRevisionTouch = touch)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shelf")
                    require(created is AppResult.Success)
                    owner.addBookToCollection(created.data.id, BookId("b1")).let {
                        require(it is AppResult.Success)
                    }
                    touch.touched.clear()

                    owner.removeBookFromCollection(created.data.id, BookId("b1")).let {
                        require(it is AppResult.Success)
                    }

                    // Removing the last real membership returns the book to ALL_BOOKS (exclusivity
                    // invariant), so its revision is bumped again by that restore — only b1 is ever
                    // touched. `distinct` tolerates the legitimate second bump for the same book.
                    touch.touched.distinct() shouldContainExactly listOf("b1")
                }
            }
        }

        test("setBookCollections touches the book once when membership changes") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestBook(bookId = "b1")
                runTest(UnconfinedTestDispatcher()) {
                    val touch = FakeBookRevisionTouch()
                    val service = makeCollectionService(db, bookRevisionTouch = touch)
                    val admin = service.actAs("u1", UserRole.ADMIN)
                    val a = admin.createCollection("test-library", "A")
                    require(a is AppResult.Success)
                    val b = admin.createCollection("test-library", "B")
                    require(b is AppResult.Success)
                    admin.addBookToCollection(a.data.id, BookId("b1")).let {
                        require(it is AppResult.Success)
                    }
                    touch.touched.clear()

                    admin.setBookCollections(BookId("b1"), listOf(b.data.id)).let {
                        require(it is AppResult.Success)
                    }

                    touch.touched shouldContainExactly listOf("b1")
                }
            }
        }

        test("setBookCollections does not touch when the membership is unchanged") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestBook(bookId = "b1")
                runTest(UnconfinedTestDispatcher()) {
                    val touch = FakeBookRevisionTouch()
                    val service = makeCollectionService(db, bookRevisionTouch = touch)
                    val admin = service.actAs("u1", UserRole.ADMIN)
                    val a = admin.createCollection("test-library", "A")
                    require(a is AppResult.Success)
                    admin.addBookToCollection(a.data.id, BookId("b1")).let {
                        require(it is AppResult.Success)
                    }
                    touch.touched.clear()

                    // Set to the IDENTICAL current membership → added/removed both empty → guard skips the touch.
                    admin.setBookCollections(BookId("b1"), listOf(a.data.id)).let {
                        require(it is AppResult.Success)
                    }

                    touch.touched.shouldBeEmpty()
                }
            }
        }

        test("releaseBooks touches each released book's revision") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1", userRole = UserRoleColumn.ADMIN)
                sql.seedTestBook(bookId = "b1")
                runTest(UnconfinedTestDispatcher()) {
                    val touch = FakeBookRevisionTouch()
                    val service = makeCollectionService(db, bookRevisionTouch = touch)
                    val admin = service.actAs("u1", UserRole.ADMIN)
                    val inbox = admin.getOrCreateInbox("test-library")
                    require(inbox is AppResult.Success)
                    admin.addBookToCollection(inbox.data.id, BookId("b1")).let {
                        require(it is AppResult.Success)
                    }
                    touch.touched.clear()

                    admin.releaseBooks("test-library", mapOf("b1" to emptyList<String>())).let {
                        require(it is AppResult.Success)
                    }

                    touch.touched shouldContainExactly listOf("b1")
                }
            }
        }

        test("deleteCollection touches every affected book's revision") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestBook(bookId = "b1")
                sql.seedTestBook(bookId = "b2")
                sql.seedTestBook(bookId = "b3")
                runTest(UnconfinedTestDispatcher()) {
                    val touch = FakeBookRevisionTouch()
                    val service = makeCollectionService(db, bookRevisionTouch = touch)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shelf")
                    require(created is AppResult.Success)
                    listOf("b1", "b2", "b3").forEach { bookId ->
                        owner.addBookToCollection(created.data.id, BookId(bookId)).let {
                            require(it is AppResult.Success)
                        }
                    }
                    touch.touched.clear()

                    owner.deleteCollection(created.data.id).let {
                        require(it is AppResult.Success)
                    }

                    // Deleting the collection returns each book to ALL_BOOKS and bumps its revision;
                    // reconcile can legitimately double-bump a flipped book, so distinct() first.
                    touch.touched.distinct() shouldContainExactlyInAnyOrder listOf("b1", "b2", "b3")
                }
            }
        }
    })

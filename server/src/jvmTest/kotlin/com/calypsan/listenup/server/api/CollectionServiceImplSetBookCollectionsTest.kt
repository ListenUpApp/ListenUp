@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.ControlFrame
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.FakeBookRevisionTouch
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Integration tests for [CollectionServiceImpl.setBookCollections] — the access-aware,
 * admin-only replace-set of a book's collection memberships.
 *
 * Uses a real in-memory Flyway-migrated SQLite database + real repositories; no mocks. The
 * acting caller is supplied via a [PrincipalProvider] stub; [actAs] rebinds the service to a
 * chosen `(userId, role)`. The AccessChanged emission test probes [ChangeBus.subscribeControl]
 * directly, mirroring [AccessChangedEmissionTest].
 */
class CollectionServiceImplSetBookCollectionsTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        data class Harness(
            val service: CollectionServiceImpl,
            val bus: ChangeBus,
        )

        fun makeHarness(db: Database): Harness {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo = CollectionRepository(db = db, bus = bus, registry = registry)
            val collectionBookRepo = CollectionBookRepository(db = db, bus = bus, registry = registry)
            val grantRepo = CollectionGrantRepository(db = db, bus = bus, registry = registry)
            val accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo)
            val service =
                CollectionServiceImpl(
                    collectionRepo = collectionRepo,
                    collectionBookRepo = collectionBookRepo,
                    grantRepo = grantRepo,
                    accessPolicy = accessPolicy,
                    bus = bus,
                    db = db,
                    clock = fixedClock,
                    permissionPolicy = UserPermissionPolicy(db.asSqlDatabase()),
                    bookRevisionTouch = FakeBookRevisionTouch(),
                    principal = principalFor("u1"),
                )
            return Harness(service, bus)
        }

        fun CollectionServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): CollectionServiceImpl = copyWith(principalFor(userId, role))

        test("setBookCollections replaces memberships (adds new, soft-deletes removed)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                seedTestBook("book1")
                runTest {
                    val (service, _) = makeHarness(db)
                    val admin = service.actAs("admin", UserRole.ADMIN)

                    val c1 = admin.createCollection("test-library", "C1")
                    val c2 = admin.createCollection("test-library", "C2")
                    require(c1 is AppResult.Success)
                    require(c2 is AppResult.Success)

                    // Book starts in [c1].
                    admin.addBookToCollection(c1.data.id, BookId("book1")) shouldBe AppResult.Success(Unit)

                    // set [c1, c2] → c1 kept, c2 added.
                    admin.setBookCollections(BookId("book1"), listOf(c1.data.id, c2.data.id)) shouldBe
                        AppResult.Success(Unit)
                    admin.listCollectionBooks(c1.data.id).let {
                        require(it is AppResult.Success)
                        it.data shouldBe listOf(BookId("book1"))
                    }
                    admin.listCollectionBooks(c2.data.id).let {
                        require(it is AppResult.Success)
                        it.data shouldBe listOf(BookId("book1"))
                    }

                    // set [c2] → c1 removed.
                    admin.setBookCollections(BookId("book1"), listOf(c2.data.id)) shouldBe
                        AppResult.Success(Unit)
                    admin.listCollectionBooks(c1.data.id).let {
                        require(it is AppResult.Success)
                        it.data shouldHaveSize 0
                    }
                    admin.listCollectionBooks(c2.data.id).let {
                        require(it is AppResult.Success)
                        it.data shouldBe listOf(BookId("book1"))
                    }
                }
            }
        }

        test("setBookCollections is admin-only") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                seedTestUser("u1")
                seedTestBook("book1")
                runTest {
                    val (service, _) = makeHarness(db)
                    val c1 = service.actAs("admin", UserRole.ADMIN).createCollection("test-library", "C1")
                    require(c1 is AppResult.Success)

                    val member = service.actAs("u1", UserRole.MEMBER)
                    val result = member.setBookCollections(BookId("book1"), listOf(c1.data.id))
                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<CollectionError.Forbidden>()
                }
            }
        }

        test("setBookCollections rejects unknown/soft-deleted target collection") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                seedTestBook("book1")
                runTest {
                    val (service, _) = makeHarness(db)
                    val admin = service.actAs("admin", UserRole.ADMIN)

                    val result = admin.setBookCollections(BookId("book1"), listOf(CollectionId("ghost")))
                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<CollectionError.NotFound>()

                    // A soft-deleted collection is no longer a valid target.
                    val c1 = admin.createCollection("test-library", "C1")
                    require(c1 is AppResult.Success)
                    admin.deleteCollection(c1.data.id) shouldBe AppResult.Success(Unit)

                    val deleted = admin.setBookCollections(BookId("book1"), listOf(c1.data.id))
                    require(deleted is AppResult.Failure)
                    deleted.error.shouldBeInstanceOf<CollectionError.NotFound>()
                }
            }
        }

        test("setBookCollections rejects unknown book") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val (service, _) = makeHarness(db)
                    val admin = service.actAs("admin", UserRole.ADMIN)
                    val c1 = admin.createCollection("test-library", "C1")
                    require(c1 is AppResult.Success)

                    val result = admin.setBookCollections(BookId("ghost-book"), listOf(c1.data.id))
                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<CollectionError.BookNotFound>()
                }
            }
        }

        test("setBookCollections emits AccessChanged to members of added AND removed collections") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                seedTestUser("u1")
                seedTestUser("u2")
                seedTestUser("u3")
                seedTestBook("book1")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(db)
                    val admin = service.actAs("admin", UserRole.ADMIN)

                    // u1 owns c1 (read-shared to u2); u1 owns c2 (read-shared to u3).
                    val u1 = service.actAs("u1")
                    val c1 = u1.createCollection("test-library", "C1")
                    val c2 = u1.createCollection("test-library", "C2")
                    require(c1 is AppResult.Success)
                    require(c2 is AppResult.Success)
                    u1.shareCollection(c1.data.id, "u2", SharePermission.Read).let { require(it is AppResult.Success) }
                    u1.shareCollection(c2.data.id, "u3", SharePermission.Read).let { require(it is AppResult.Success) }

                    // Book starts in c1.
                    admin.setBookCollections(BookId("book1"), listOf(c1.data.id)) shouldBe AppResult.Success(Unit)

                    // Subscribe only after the initial placement so we observe the move's frames alone.
                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    drainControlFrames() // ensure the unconfined collector is subscribed before the action publishes

                    // Move book [c1] → [c2]: u2 loses access (c1 removed), u3 gains access (c2 added),
                    // plus the owner (u1) of both touched collections.
                    admin.setBookCollections(BookId("book1"), listOf(c2.data.id)) shouldBe AppResult.Success(Unit)
                    drainControlFrames()

                    frames.map { it.userId } shouldContainExactlyInAnyOrder listOf("u1", "u2", "u3")
                    frames.forEach { it.control shouldBe SyncControl.AccessChanged }
                }
            }
        }
    })

/**
 * Lets the unconfined `backgroundScope` control-frame collector drain before asserting on the
 * captured `frames` — the published `AccessChanged` frame is already in the `SharedFlow` when the
 * mutating call returns, but the `launchIn(backgroundScope)` collector (on `UnconfinedTestDispatcher`)
 * may not have been scheduled yet. Yielding deterministically dispatches the pending continuations.
 * See `AccessChangedEmissionTest.drainControlFrames` for the full rationale.
 */
private suspend fun drainControlFrames() {
    repeat(8) { kotlinx.coroutines.yield() }
}

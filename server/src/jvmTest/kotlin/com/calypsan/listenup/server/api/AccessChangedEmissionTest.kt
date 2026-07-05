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
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ControlFrame
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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Verifies the per-user [SyncControl.AccessChanged] control signal is published — and to
 * exactly the right user(s) — at each access-mutation point in [CollectionServiceImpl].
 *
 * Asserts via a direct probe of [ChangeBus.subscribeControl] rather than a full SSE round-trip:
 * the firehose's per-user filter is exercised by the firehose suite; here we pin the emission
 * contract — which frame, addressed to whom — at the source.
 */
class AccessChangedEmissionTest :
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

        fun makeHarness(db: SqlTestDatabases): Harness {
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
            val service =
                CollectionServiceImpl(
                    collectionRepo = collectionRepo,
                    collectionBookRepo = collectionBookRepo,
                    grantRepo = grantRepo,
                    accessPolicy = accessPolicy,
                    permissionPolicy = UserPermissionPolicy(db.sql),
                    bus = bus,
                    sql = db.sql,
                    clock = fixedClock,
                    bookRevisionTouch = FakeBookRevisionTouch(),
                    principal = principalFor("u1"),
                )
            return Harness(service, bus)
        }

        fun CollectionServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): CollectionServiceImpl = copyWith(principalFor(userId, role))

        test("shareCollection emits AccessChanged to the share target only") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(this@withSqlDatabase)
                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    drainControlFrames() // ensure the unconfined collector is subscribed before the action publishes

                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)

                    owner.shareCollection(created.data.id, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }
                    drainControlFrames()

                    frames shouldContainExactlyInAnyOrder listOf(ControlFrame(SyncControl.AccessChanged(), "u2"))
                }
            }
        }

        test("updateShare emits AccessChanged to the recipient") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(this@withSqlDatabase)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    owner.shareCollection(created.data.id, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }

                    // Subscribe only after the initial share so we observe the update's frame alone.
                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    drainControlFrames() // ensure the unconfined collector is subscribed before the action publishes

                    owner.updateShare(created.data.id, "u2", SharePermission.Write).let {
                        require(it is AppResult.Success)
                    }
                    drainControlFrames()

                    frames shouldContainExactlyInAnyOrder listOf(ControlFrame(SyncControl.AccessChanged(), "u2"))
                }
            }
        }

        test("revokeShare emits AccessChanged to the ex-target") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(this@withSqlDatabase)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    owner.shareCollection(created.data.id, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }

                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    drainControlFrames() // ensure the unconfined collector is subscribed before the action publishes

                    owner.revokeShare(created.data.id, "u2") shouldBe AppResult.Success(Unit)
                    drainControlFrames()

                    frames shouldContainExactlyInAnyOrder listOf(ControlFrame(SyncControl.AccessChanged(), "u2"))
                }
            }
        }

        test("revokeShare on a non-existent share emits nothing") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(this@withSqlDatabase)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)

                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    drainControlFrames() // ensure the unconfined collector is subscribed before the action publishes

                    owner.revokeShare(created.data.id, "u2") shouldBe AppResult.Success(Unit)
                    drainControlFrames()

                    frames shouldBe emptyList()
                }
            }
        }

        test("releaseBooks emits AccessChanged to users gaining access (target owner + share recipients)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("book1")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(this@withSqlDatabase)
                    val admin = service.actAs("admin", UserRole.ADMIN)

                    // u1 owns the target collection; u2 holds a read-share on it.
                    val u1 = service.actAs("u1")
                    val target = u1.createCollection("test-library", "Target")
                    require(target is AppResult.Success)
                    u1.shareCollection(target.data.id, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }

                    service.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)

                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    drainControlFrames() // ensure the unconfined collector is subscribed before the action publishes

                    admin.releaseBooks(
                        "test-library",
                        mapOf("book1" to listOf(target.data.id.value)),
                    ) shouldBe AppResult.Success(Unit)
                    drainControlFrames()

                    frames.map { it.userId } shouldContainExactlyInAnyOrder listOf("u1", "u2")
                    frames.forEach { it.control shouldBe SyncControl.AccessChanged() }
                }
            }
        }

        test("addBookToCollection emits AccessChanged to the collection's owner + share recipients") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("book1")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(this@withSqlDatabase)
                    val u1 = service.actAs("u1")
                    val created = u1.createCollection("test-library", "Shelf")
                    require(created is AppResult.Success)
                    u1.shareCollection(created.data.id, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }

                    // Subscribe after the share so we observe only the add's frames.
                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    drainControlFrames() // ensure the unconfined collector is subscribed before the action publishes

                    u1.addBookToCollection(created.data.id, BookId("book1")) shouldBe AppResult.Success(Unit)
                    drainControlFrames()

                    frames.map { it.userId } shouldContainExactlyInAnyOrder listOf("u1", "u2")
                    frames.forEach { it.control shouldBe SyncControl.AccessChanged() }
                }
            }
        }

        test("removeBookFromCollection emits AccessChanged to the collection's owner + share recipients") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("book1")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(this@withSqlDatabase)
                    val u1 = service.actAs("u1")
                    val created = u1.createCollection("test-library", "Shelf")
                    require(created is AppResult.Success)
                    u1.shareCollection(created.data.id, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }
                    u1.addBookToCollection(created.data.id, BookId("book1")) shouldBe AppResult.Success(Unit)

                    // Subscribe after the add so we observe only the remove's frames.
                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)
                    drainControlFrames() // ensure the unconfined collector is subscribed before the action publishes

                    u1.removeBookFromCollection(created.data.id, BookId("book1")) shouldBe AppResult.Success(Unit)
                    drainControlFrames()

                    frames.map { it.userId } shouldContainExactlyInAnyOrder listOf("u1", "u2")
                    frames.forEach { it.control shouldBe SyncControl.AccessChanged() }
                }
            }
        }

        test("releaseBooks rejects a target collection in a different library") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("book1")
                runTest {
                    val (service, _) = makeHarness(this@withSqlDatabase)
                    val admin = service.actAs("admin", UserRole.ADMIN)
                    service.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)

                    val result =
                        admin.releaseBooks(
                            "test-library",
                            mapOf("book1" to listOf("does-not-exist")),
                        )
                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<CollectionError.NotFound>()
                }
            }
        }
    })

/**
 * Lets the unconfined `backgroundScope` control-frame collector drain before asserting on the
 * captured `frames`.
 *
 * The mutating service methods publish their `AccessChanged` frame (via `ChangeBus.publishControl`'s
 * `tryEmit`) before the suspend call returns, so by the time we reach the assertion the frame is
 * already in the `SharedFlow` buffer — but the `launchIn(backgroundScope)` collector that appends it
 * to the test's `frames` list runs on the `UnconfinedTestDispatcher` and has not necessarily been
 * scheduled yet. The permission read now hops to `Dispatchers.IO` (the SQLDelight
 * `UserPermissionPolicy`), which reorders that scheduling relative to the assertion. Yielding a few
 * times deterministically dispatches the pending unconfined continuations, so the collector observes
 * the already-emitted frame — no real-time sleep, no busy-wait.
 */
private suspend fun drainControlFrames() {
    repeat(8) { kotlinx.coroutines.yield() }
}

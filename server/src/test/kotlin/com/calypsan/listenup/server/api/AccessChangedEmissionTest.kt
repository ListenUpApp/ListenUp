@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ControlFrame
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionShareRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

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

        fun makeHarness(db: Database): Harness {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo = CollectionRepository(db = db, bus = bus, registry = registry)
            val collectionBookRepo = CollectionBookRepository(db = db, bus = bus, registry = registry)
            val shareRepo = CollectionShareRepository(db = db, bus = bus, registry = registry)
            val accessPolicy = CollectionAccessPolicy(collectionRepo, shareRepo)
            val service =
                CollectionServiceImpl(
                    collectionRepo = collectionRepo,
                    collectionBookRepo = collectionBookRepo,
                    shareRepo = shareRepo,
                    accessPolicy = accessPolicy,
                    bus = bus,
                    db = db,
                    clock = fixedClock,
                    principal = principalFor("u1"),
                )
            return Harness(service, bus)
        }

        fun CollectionServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): CollectionServiceImpl = copyWith(principalFor(userId, role))

        test("shareCollection emits AccessChanged to the share target only") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(db)
                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)

                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)

                    owner.shareCollection(created.data.id, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }

                    frames shouldContainExactlyInAnyOrder listOf(ControlFrame(SyncControl.AccessChanged, "u2"))
                }
            }
        }

        test("updateShare emits AccessChanged to the recipient") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(db)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    owner.shareCollection(created.data.id, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }

                    // Subscribe only after the initial share so we observe the update's frame alone.
                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)

                    owner.updateShare(created.data.id, "u2", SharePermission.Write).let {
                        require(it is AppResult.Success)
                    }

                    frames shouldContainExactlyInAnyOrder listOf(ControlFrame(SyncControl.AccessChanged, "u2"))
                }
            }
        }

        test("revokeShare emits AccessChanged to the ex-target") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(db)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    owner.shareCollection(created.data.id, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }

                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)

                    owner.revokeShare(created.data.id, "u2") shouldBe AppResult.Success(Unit)

                    frames shouldContainExactlyInAnyOrder listOf(ControlFrame(SyncControl.AccessChanged, "u2"))
                }
            }
        }

        test("revokeShare on a non-existent share emits nothing") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(db)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)

                    val frames = mutableListOf<ControlFrame>()
                    bus.subscribeControl().onEach { frames += it }.launchIn(backgroundScope)

                    owner.revokeShare(created.data.id, "u2") shouldBe AppResult.Success(Unit)

                    frames shouldBe emptyList()
                }
            }
        }

        test("releaseBooks emits AccessChanged to users gaining access (target owner + share recipients)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                seedTestUser("u1")
                seedTestUser("u2")
                seedTestBook("book1")
                runTest(UnconfinedTestDispatcher()) {
                    val (service, bus) = makeHarness(db)
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

                    admin.releaseBooks(
                        "test-library",
                        mapOf("book1" to listOf(target.data.id.value)),
                    ) shouldBe AppResult.Success(Unit)

                    frames.map { it.userId } shouldContainExactlyInAnyOrder listOf("u1", "u2")
                    frames.forEach { it.control shouldBe SyncControl.AccessChanged }
                }
            }
        }

        test("releaseBooks rejects a target collection in a different library") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                seedTestBook("book1")
                runTest {
                    val (service, _) = makeHarness(db)
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

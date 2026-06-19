@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for [CollectionAccessPolicy] — the collection-level access truth table.
 *
 * Each test seeds a real in-memory database (library + collection + optional share)
 * via the Task 3 repositories, then asserts the [CollectionAccessPolicy.Decision]
 * triple `(canAccess, permission, isOwner)`.
 */
class CollectionAccessPolicyTest :
    FunSpec({

        test("owner gets WRITE + isOwner") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo = CollectionRepository(db = this, bus = bus, registry = registry)
                val grantRepo = CollectionGrantRepository(db = this, bus = bus, registry = registry)
                val policy = CollectionAccessPolicy(collectionRepo, grantRepo)

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "Owned",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    val decision = policy.decide("user1", UserRoleColumn.MEMBER, "col1")

                    decision.canAccess shouldBe true
                    decision.permission shouldBe SharePermission.Write
                    decision.isOwner shouldBe true
                }
            }
        }

        test("admin bypasses to WRITE") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo = CollectionRepository(db = this, bus = bus, registry = registry)
                val grantRepo = CollectionGrantRepository(db = this, bus = bus, registry = registry)
                val policy = CollectionAccessPolicy(collectionRepo, grantRepo)

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "Owned by someone else",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    val decision = policy.decide("admin-user", UserRoleColumn.ADMIN, "col1")

                    decision.canAccess shouldBe true
                    decision.permission shouldBe SharePermission.Write
                    decision.isOwner shouldBe false
                }
            }
        }

        test("active read-share gets READ") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestUser("user2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo = CollectionRepository(db = this, bus = bus, registry = registry)
                val grantRepo = CollectionGrantRepository(db = this, bus = bus, registry = registry)
                val policy = CollectionAccessPolicy(collectionRepo, grantRepo)

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "Shared",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "share1",
                            collectionId = "col1",
                            sharedWithUserId = "user2",
                            sharedByUserId = "user1",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    val decision = policy.decide("user2", UserRoleColumn.MEMBER, "col1")

                    decision.canAccess shouldBe true
                    decision.permission shouldBe SharePermission.Read
                    decision.isOwner shouldBe false
                }
            }
        }

        test("active write-share gets WRITE") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestUser("user2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo = CollectionRepository(db = this, bus = bus, registry = registry)
                val grantRepo = CollectionGrantRepository(db = this, bus = bus, registry = registry)
                val policy = CollectionAccessPolicy(collectionRepo, grantRepo)

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "Shared",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "share1",
                            collectionId = "col1",
                            sharedWithUserId = "user2",
                            sharedByUserId = "user1",
                            permission = SharePermission.Write,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    val decision = policy.decide("user2", UserRoleColumn.MEMBER, "col1")

                    decision.canAccess shouldBe true
                    decision.permission shouldBe SharePermission.Write
                    decision.isOwner shouldBe false
                }
            }
        }

        test("revoked (soft-deleted) share denies") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestUser("user2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo = CollectionRepository(db = this, bus = bus, registry = registry)
                val grantRepo = CollectionGrantRepository(db = this, bus = bus, registry = registry)
                val policy = CollectionAccessPolicy(collectionRepo, grantRepo)

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "Shared",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "share1",
                            collectionId = "col1",
                            sharedWithUserId = "user2",
                            sharedByUserId = "user1",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    grantRepo.softDeleteGrant("col1", "user2")

                    val decision = policy.decide("user2", UserRoleColumn.MEMBER, "col1")

                    decision.canAccess shouldBe false
                    decision.permission shouldBe SharePermission.Read
                    decision.isOwner shouldBe false
                }
            }
        }

        test("no relationship denies") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo = CollectionRepository(db = this, bus = bus, registry = registry)
                val grantRepo = CollectionGrantRepository(db = this, bus = bus, registry = registry)
                val policy = CollectionAccessPolicy(collectionRepo, grantRepo)

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "Owned",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    val decision = policy.decide("stranger", UserRoleColumn.MEMBER, "col1")

                    decision.canAccess shouldBe false
                    decision.permission shouldBe SharePermission.Read
                    decision.isOwner shouldBe false
                }
            }
        }

        test("missing collection denies") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo = CollectionRepository(db = this, bus = bus, registry = registry)
                val grantRepo = CollectionGrantRepository(db = this, bus = bus, registry = registry)
                val policy = CollectionAccessPolicy(collectionRepo, grantRepo)

                runTest {
                    val decision = policy.decide("user1", UserRoleColumn.MEMBER, "nonexistent")

                    decision.canAccess shouldBe false
                    decision.permission shouldBe SharePermission.Read
                    decision.isOwner shouldBe false
                }
            }
        }
    })

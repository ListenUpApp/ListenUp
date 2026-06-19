@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.CollectionsTable
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FakeBookRevisionTouch
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Integration tests for [CollectionServiceImpl.getOrCreateSystemCollection].
 *
 * Drives the generalized find-or-create against a real in-memory Flyway-migrated SQLite
 * database + real repositories; no mocks. Asserts the server-only `collections.type`
 * column is set on creation, the name follows the system type, and the create is idempotent.
 */
class SystemCollectionTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun makeService(db: Database): CollectionServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo = CollectionRepository(db = db, bus = bus, registry = registry)
            val collectionBookRepo = CollectionBookRepository(db = db, bus = bus, registry = registry)
            val grantRepo = CollectionGrantRepository(db = db, bus = bus, registry = registry)
            val accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo)
            return CollectionServiceImpl(
                collectionRepo = collectionRepo,
                collectionBookRepo = collectionBookRepo,
                grantRepo = grantRepo,
                accessPolicy = accessPolicy,
                permissionPolicy = UserPermissionPolicy(db),
                bus = bus,
                db = db,
                clock = fixedClock,
                bookRevisionTouch = FakeBookRevisionTouch(),
                principal = PrincipalProvider { null },
            )
        }

        /** Reads the server-only `collections.type` column for [collectionId], or null if absent. */
        suspend fun Database.typeColumnOf(collectionId: String): String? =
            suspendTransaction(this) {
                CollectionsTable
                    .selectAll()
                    .where { CollectionsTable.id eq collectionId }
                    .firstOrNull()
                    ?.get(CollectionsTable.type)
            }

        /** Reads the back-compat `collections.is_inbox` column for [collectionId]. */
        suspend fun Database.isInboxColumnOf(collectionId: String): Boolean? =
            suspendTransaction(this) {
                CollectionsTable
                    .selectAll()
                    .where { CollectionsTable.id eq collectionId }
                    .firstOrNull()
                    ?.get(CollectionsTable.isInbox)
            }

        test("getOrCreateSystemCollection creates ALL_BOOKS with type column and name set") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val service = makeService(db)

                    val result = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(result is AppResult.Success)
                    result.data.name shouldBe "All Books"
                    result.data.ownerId shouldBe UserId("admin")

                    db.typeColumnOf(result.data.id.value) shouldBe "ALL_BOOKS"
                }
            }
        }

        test("getOrCreateSystemCollection is idempotent — second call returns the same collection") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val service = makeService(db)

                    val first = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(first is AppResult.Success)
                    val second = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(second is AppResult.Success)

                    second.data.id shouldBe first.data.id
                }
            }
        }

        test("getOrCreateSystemCollection INBOX is distinct from ALL_BOOKS and back-compat is_inbox is set") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val service = makeService(db)

                    val allBooks = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)

                    val inbox = service.getOrCreateSystemCollection("test-library", SystemCollectionType.INBOX)
                    require(inbox is AppResult.Success)
                    inbox.data.name shouldBe "Inbox"
                    inbox.data.isInbox shouldBe true

                    (inbox.data.id == allBooks.data.id) shouldBe false

                    db.typeColumnOf(inbox.data.id.value) shouldBe "INBOX"
                    db.isInboxColumnOf(inbox.data.id.value) shouldBe true
                }
            }
        }
    })

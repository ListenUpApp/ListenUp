package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.api.SYSTEM_OWNER_ID
import com.calypsan.listenup.server.db.CollectionsTable
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Verifies that [LibraryRegistry.currentLibrary] — on a fresh (empty) database — bootstraps
 * the singleton library **and** creates the two system collections (ALL_BOOKS and INBOX) in the
 * same transaction, both owned by the [SYSTEM_OWNER_ID] sentinel.
 *
 * These are in-memory Flyway-migrated SQLite databases: no mocks, no stubs.
 */
class LibraryRegistryBootstrapSystemCollectionsTest :
    FunSpec({

        test("bootstrapping a fresh library creates exactly one ALL_BOOKS collection owned by system") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val registry = LibraryRegistry(db)
                    val libraryId = registry.currentLibrary()

                    val rows =
                        suspendTransaction(db) {
                            CollectionsTable
                                .selectAll()
                                .where { CollectionsTable.type eq "ALL_BOOKS" }
                                .toList()
                        }

                    rows.size shouldBe 1
                    rows[0][CollectionsTable.libraryId] shouldBe libraryId.value
                    rows[0][CollectionsTable.ownerId] shouldBe SYSTEM_OWNER_ID
                    rows[0][CollectionsTable.name] shouldBe "All Books"
                    rows[0][CollectionsTable.isInbox] shouldBe false
                    rows[0][CollectionsTable.revision] shouldBeGreaterThan 0L
                }
            }
        }

        test("bootstrapping a fresh library creates exactly one INBOX collection owned by system") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val registry = LibraryRegistry(db)
                    val libraryId = registry.currentLibrary()

                    val rows =
                        suspendTransaction(db) {
                            CollectionsTable
                                .selectAll()
                                .where { CollectionsTable.type eq "INBOX" }
                                .toList()
                        }

                    rows.size shouldBe 1
                    rows[0][CollectionsTable.libraryId] shouldBe libraryId.value
                    rows[0][CollectionsTable.ownerId] shouldBe SYSTEM_OWNER_ID
                    rows[0][CollectionsTable.name] shouldBe "Inbox"
                    rows[0][CollectionsTable.isInbox] shouldBe true
                    rows[0][CollectionsTable.revision] shouldBeGreaterThan 0L
                }
            }
        }

        test("ALL_BOOKS and INBOX collections get distinct ids") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val registry = LibraryRegistry(db)
                    registry.currentLibrary()

                    val (allBooksId, inboxId) =
                        suspendTransaction(db) {
                            val all =
                                CollectionsTable
                                    .selectAll()
                                    .where { CollectionsTable.type eq "ALL_BOOKS" }
                                    .first()[CollectionsTable.id]
                            val inbox =
                                CollectionsTable
                                    .selectAll()
                                    .where { CollectionsTable.type eq "INBOX" }
                                    .first()[CollectionsTable.id]
                            all to inbox
                        }

                    allBooksId shouldNotBe inboxId
                }
            }
        }
    })

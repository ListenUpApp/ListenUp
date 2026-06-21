package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.api.SYSTEM_OWNER_ID
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Verifies that [LibraryRegistry.currentLibrary] — on a fresh (empty) database — bootstraps
 * the singleton library **and** creates the two system collections (ALL_BOOKS and INBOX) in the
 * same transaction, both owned by the [SYSTEM_OWNER_ID] sentinel.
 *
 * These are Flyway-migrated SQLite databases: no mocks, no stubs.
 */
class LibraryRegistryBootstrapSystemCollectionsTest :
    FunSpec({

        test("bootstrapping a fresh library creates exactly one ALL_BOOKS collection owned by system") {
            withSqlDatabase {
                runTest {
                    val registry = LibraryRegistry(sql)
                    val libraryId = registry.currentLibrary()

                    val rows =
                        sql.collectionsQueries
                            .selectLiveSystemForLibrary(library_id = libraryId.value, type = "ALL_BOOKS")
                            .executeAsList()

                    rows.size shouldBe 1
                    rows[0].library_id shouldBe libraryId.value
                    rows[0].owner_id shouldBe SYSTEM_OWNER_ID
                    rows[0].name shouldBe "All Books"
                    rows[0].type shouldBe "ALL_BOOKS"
                    rows[0].revision shouldBeGreaterThan 0L
                }
            }
        }

        test("bootstrapping a fresh library creates exactly one INBOX collection owned by system") {
            withSqlDatabase {
                runTest {
                    val registry = LibraryRegistry(sql)
                    val libraryId = registry.currentLibrary()

                    val rows =
                        sql.collectionsQueries
                            .selectLiveSystemForLibrary(library_id = libraryId.value, type = "INBOX")
                            .executeAsList()

                    rows.size shouldBe 1
                    rows[0].library_id shouldBe libraryId.value
                    rows[0].owner_id shouldBe SYSTEM_OWNER_ID
                    rows[0].name shouldBe "Inbox"
                    rows[0].type shouldBe "INBOX"
                    rows[0].revision shouldBeGreaterThan 0L
                }
            }
        }

        test("ALL_BOOKS and INBOX collections get distinct ids") {
            withSqlDatabase {
                runTest {
                    val registry = LibraryRegistry(sql)
                    val libraryId = registry.currentLibrary()

                    val allBooksId =
                        sql.collectionsQueries
                            .selectLiveSystemForLibrary(library_id = libraryId.value, type = "ALL_BOOKS")
                            .executeAsOne()
                            .id
                    val inboxId =
                        sql.collectionsQueries
                            .selectLiveSystemForLibrary(library_id = libraryId.value, type = "INBOX")
                            .executeAsOne()
                            .id

                    allBooksId shouldNotBe inboxId
                }
            }
        }
    })

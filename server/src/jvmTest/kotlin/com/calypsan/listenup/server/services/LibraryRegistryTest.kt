package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for [LibraryRegistry].
 *
 * The library is a singleton: it always exists. When no row is present, the registry
 * creates a path-less "Library" row (no folder). Folders are added separately by
 * [com.calypsan.listenup.server.Application.bootstrapLibraries] or user onboarding.
 */
class LibraryRegistryTest :
    FunSpec({

        test("currentLibrary creates a single path-less library when none exists and no env path") {
            withSqlDatabase {
                runTest {
                    val registry = LibraryRegistry(sql)

                    val id = registry.currentLibrary()

                    // Exactly one non-deleted library row named "Library"
                    val rows = sql.librariesQueries.selectAllLive().executeAsList()
                    rows.size shouldBe 1
                    rows[0].name shouldBe "Library"
                    rows[0].id shouldBe id.value

                    // No folder rows created
                    sql.libraryFoldersQueries.countAll().executeAsOne() shouldBe 0L

                    // Cached: second call returns the same id without inserting another row
                    registry.currentLibrary() shouldBe id
                    sql.librariesQueries
                        .selectAllLive()
                        .executeAsList()
                        .size shouldBe 1
                }
            }
        }

        test("bootstraps a new library row when no library exists") {
            withSqlDatabase {
                runTest {
                    val registry = LibraryRegistry(sql)
                    val id = registry.currentLibrary()
                    val count =
                        sql.librariesQueries
                            .selectAllLive()
                            .executeAsList()
                            .count { it.id == id.value }
                    count shouldBe 1
                }
            }
        }

        test("returns existing library when one already exists") {
            withSqlDatabase {
                val now = System.currentTimeMillis()
                sql.transaction {
                    sql.librariesQueries.insert(
                        id = "lib-existing",
                        name = "Existing",
                        metadata_precedence = "embedded,abs,sidecar",
                        access_mode = "shared",
                        created_by_user_id = null,
                        created_at = now,
                        revision = 0L,
                        updated_at = now,
                        deleted_at = null,
                        client_op_id = null,
                    )
                }
                runTest {
                    val registry = LibraryRegistry(sql)
                    registry.currentLibrary() shouldBe LibraryId("lib-existing")
                }
            }
        }

        test("cached after first call — subsequent calls don't hit the DB") {
            withSqlDatabase {
                runTest {
                    val registry = LibraryRegistry(sql)
                    val first = registry.currentLibrary()
                    sql.transaction { sql.librariesQueries.deleteAll() }
                    registry.currentLibrary() shouldBe first
                }
            }
        }
    })

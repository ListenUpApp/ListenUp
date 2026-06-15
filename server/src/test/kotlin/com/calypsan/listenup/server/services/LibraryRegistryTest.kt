package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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
            withInMemoryDatabase {
                val db = this
                runTest {
                    val registry = LibraryRegistry(db)

                    val id = registry.currentLibrary()

                    transaction(db) {
                        // Exactly one non-deleted library row named "Library"
                        val rows = LibraryTable.selectAll()
                            .where { LibraryTable.deletedAt.isNull() }
                            .toList()
                        rows.size shouldBe 1
                        rows[0][LibraryTable.name] shouldBe "Library"
                        rows[0][LibraryTable.id] shouldBe id.value

                        // No folder rows created
                        LibraryFolderTable.selectAll().count() shouldBe 0L
                    }

                    // Cached: second call returns the same id without inserting another row
                    registry.currentLibrary() shouldBe id
                    transaction(db) {
                        LibraryTable.selectAll().where { LibraryTable.deletedAt.isNull() }.count() shouldBe 1L
                    }
                }
            }
        }

        test("bootstraps a new library row when no library exists") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val registry = LibraryRegistry(db)
                    val id = registry.currentLibrary()
                    transaction(db) {
                        LibraryTable.selectAll().where { LibraryTable.id eq id.value }.count() shouldBe 1L
                    }
                }
            }
        }

        test("returns existing library when one already exists") {
            withInMemoryDatabase {
                val db = this
                val now = System.currentTimeMillis()
                transaction(db) {
                    LibraryTable.insert {
                        it[LibraryTable.id] = "lib-existing"
                        it[LibraryTable.name] = "Existing"
                        it[LibraryTable.createdAt] = now
                        it[LibraryTable.updatedAt] = now
                        it[LibraryTable.revision] = 0L
                        it[LibraryTable.deletedAt] = null
                    }
                }
                runTest {
                    val registry = LibraryRegistry(db)
                    registry.currentLibrary() shouldBe LibraryId("lib-existing")
                }
            }
        }

        test("cached after first call — subsequent calls don't hit the DB") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val registry = LibraryRegistry(db)
                    val first = registry.currentLibrary()
                    transaction(db) { LibraryTable.deleteAll() }
                    registry.currentLibrary() shouldBe first
                }
            }
        }
    })

package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Tests for [LibraryRegistry] — the Books-A bootstrap shim superseded by
 * [LibraryAdminServiceImpl] in the Libraries phase (LIB-C Task 18).
 *
 * The old rootPath-keyed behaviour was removed in V20. The registry now returns
 * the first non-deleted library row, creating one if none exist.
 */
class LibraryRegistryTest :
    FunSpec({

        test("bootstraps a new library row when no library exists") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val registry = LibraryRegistry(db, env = mapOf("LISTENUP_LIBRARY_PATH" to "/mnt/audiobooks"))
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
                    val registry = LibraryRegistry(db, env = mapOf("LISTENUP_LIBRARY_PATH" to "/mnt/audiobooks"))
                    registry.currentLibrary() shouldBe LibraryId("lib-existing")
                }
            }
        }

        test("cached after first call — subsequent calls don't hit the DB") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val registry = LibraryRegistry(db, env = mapOf("LISTENUP_LIBRARY_PATH" to "/mnt/audiobooks"))
                    val first = registry.currentLibrary()
                    transaction(db) { LibraryTable.deleteAll() }
                    registry.currentLibrary() shouldBe first
                }
            }
        }
    })

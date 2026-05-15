package com.calypsan.listenup.server.services

import com.calypsan.listenup.client.core.LibraryId
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class LibraryRegistryTest :
    FunSpec({

        test("bootstraps a new library row when LISTENUP_LIBRARY_PATH is fresh") {
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

        test("returns existing library when one already exists for the path") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    LibraryTable.insert {
                        it[LibraryTable.id] = "lib-existing"
                        it[LibraryTable.name] = "Default"
                        it[LibraryTable.rootPath] = "/mnt/audiobooks"
                    }
                }
                runTest {
                    val registry = LibraryRegistry(db, env = mapOf("LISTENUP_LIBRARY_PATH" to "/mnt/audiobooks"))
                    registry.currentLibrary() shouldBe LibraryId("lib-existing")
                }
            }
        }

        test("throws when LISTENUP_LIBRARY_PATH is missing") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val registry = LibraryRegistry(db, env = emptyMap())
                    shouldThrow<IllegalStateException> { registry.currentLibrary() }
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

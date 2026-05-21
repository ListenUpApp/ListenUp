package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedenceSource
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

        test("reconciles metadata_precedence on an existing row when the configured value changed") {
            withInMemoryDatabase {
                val db = this
                // Pre-populate a row with the old (default) precedence.
                transaction(db) {
                    LibraryTable.insert {
                        it[LibraryTable.id] = "lib-stale"
                        it[LibraryTable.name] = "Default"
                        it[LibraryTable.rootPath] = "/mnt/audiobooks"
                        it[LibraryTable.metadataPrecedence] = MetadataPrecedence.DEFAULT.serialize()
                    }
                }
                // Operator has changed the env var — embedded is now highest priority.
                val changed =
                    MetadataPrecedence(
                        listOf(
                            MetadataPrecedenceSource.EMBEDDED,
                            MetadataPrecedenceSource.ABS_METADATA,
                            MetadataPrecedenceSource.SIDECAR,
                            MetadataPrecedenceSource.FILENAME,
                            MetadataPrecedenceSource.FOLDER,
                        ),
                    )
                runTest {
                    val registry =
                        LibraryRegistry(
                            db,
                            env = mapOf("LISTENUP_LIBRARY_PATH" to "/mnt/audiobooks"),
                            metadataPrecedence = changed,
                        )
                    registry.currentLibrary()
                    val stored =
                        transaction(db) {
                            LibraryTable
                                .selectAll()
                                .where { LibraryTable.id eq "lib-stale" }
                                .first()[LibraryTable.metadataPrecedence]
                        }
                    stored shouldBe changed.serialize()
                }
            }
        }
    })

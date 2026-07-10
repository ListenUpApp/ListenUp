@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sidecar

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.librarywrite.WriteJournal
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** The debounce window used across these tests — short is irrelevant, time is virtual. */
private const val WINDOW_MS = 5_000L

/**
 * Tests for [SidecarWriter] — the per-book debounced write-through pipeline: curation-dirty
 * signal → debounce → assemble → broker write → hash record. Virtual time via [runTest]
 * drives the debounce; the broker writes to a real temp library folder.
 */
class SidecarWriterTest :
    FunSpec({

        fun tempLibraryDir(): String = Files.createTempDirectory("sidecar-writer-test-").toString()

        fun SqlTestDatabases.writer(
            scope: CoroutineScope,
            libraryDir: String,
        ): SidecarWriter =
            SidecarWriter(
                db = sql,
                assembler = SidecarAssembler(),
                broker =
                    LibraryWriteBroker(
                        registry = SelfWriteRegistry { 0L },
                        journal = WriteJournal(Path(Files.createTempDirectory("sidecar-journal-").toString())),
                    ),
                writeState = SidecarWriteStateRepository(sql),
                settings = ServerSettingsRepository(sql, RegistrationPolicy.CLOSED),
                scope = scope,
                debounceMs = WINDOW_MS,
            ).also { sql.seedTestLibraryAndFolder(folderPath = libraryDir) }

        test("two markDirty calls within the window coalesce to one write after the window") {
            withSqlDatabase {
                runTest {
                    val lib = tempLibraryDir()
                    val writer = writer(backgroundScope, lib)
                    sql.seedTestBook(bookId = "book1", rootRelPath = "MyBook")
                    val target = Path(lib, "MyBook", "listenup.json")

                    writer.markDirty("book1")
                    advanceTimeBy(3_000L)
                    writer.markDirty("book1") // restarts the window — the first pending write is cancelled

                    // t = 5.5s: past the FIRST window but only 2.5s into the second — nothing written yet.
                    advanceTimeBy(2_500L)
                    SystemFileSystem.exists(target) shouldBe false

                    // t = 8.5s: past the second window — the single coalesced write has landed.
                    advanceTimeBy(3_000L)
                    writer.awaitQuiescent()
                    SystemFileSystem.exists(target) shouldBe true
                }
            }
        }

        test("the written bytes parse back to the assembled sidecar and the state row records the hash") {
            withSqlDatabase {
                runTest {
                    val lib = tempLibraryDir()
                    val writer = writer(backgroundScope, lib)
                    sql.seedTestBook(bookId = "book1", rootRelPath = "MyBook")

                    writer.markDirty("book1")
                    advanceTimeBy(WINDOW_MS + 1)
                    writer.awaitQuiescent()

                    val target = Path(lib, "MyBook", "listenup.json")
                    val bytes = SystemFileSystem.source(target).buffered().use { it.readByteArray() }
                    val parsed = SidecarJson.parseOrNull(bytes)
                    parsed.shouldNotBeNull()
                    parsed.metadata.title shouldBe "Test Book book1"

                    val state = SidecarWriteStateRepository(sql).findByBookId("book1")
                    state.shouldNotBeNull()
                    state.contentHashHex shouldBe hashBytesSha256(bytes)
                }
            }
        }

        test("a tagged book's sidecar carries the tag names, sorted") {
            withSqlDatabase {
                runTest {
                    val lib = tempLibraryDir()
                    val writer = writer(backgroundScope, lib)
                    sql.seedTestBook(bookId = "book1", rootRelPath = "MyBook")
                    sql.transaction {
                        sql.tagsQueries.insert("t1", "zeta", "zeta", 1L, 1L, 1L, null, null)
                        sql.tagsQueries.insert("t2", "alpha", "alpha", 1L, 1L, 1L, null, null)
                        sql.bookTagsQueries.insert("book1:t1", "book1", "t1", 1L, 1L, 1L, null, null)
                        sql.bookTagsQueries.insert("book1:t2", "book1", "t2", 1L, 1L, 1L, null, null)
                    }

                    writer.markDirty("book1")
                    advanceTimeBy(WINDOW_MS + 1)
                    writer.awaitQuiescent()

                    val target = Path(lib, "MyBook", "listenup.json")
                    val bytes = SystemFileSystem.source(target).buffered().use { it.readByteArray() }
                    val parsed = SidecarJson.parseOrNull(bytes)
                    parsed.shouldNotBeNull()
                    parsed.metadata.tags shouldContainExactly listOf("alpha", "zeta")
                }
            }
        }

        test("sidecar_writes_enabled=false makes markDirty a no-op — no write, no state row") {
            withSqlDatabase {
                runTest {
                    val lib = tempLibraryDir()
                    val writer = writer(backgroundScope, lib)
                    sql.seedTestBook(bookId = "book1", rootRelPath = "MyBook")
                    ServerSettingsRepository(sql, RegistrationPolicy.CLOSED)
                        .setValue("sidecar_writes_enabled", "false")

                    writer.markDirty("book1")
                    advanceTimeBy(WINDOW_MS + 1)
                    writer.awaitQuiescent()

                    SystemFileSystem.exists(Path(lib, "MyBook", "listenup.json")) shouldBe false
                    SidecarWriteStateRepository(sql).findByBookId("book1").shouldBeNull()
                }
            }
        }

        test("a broker failure parks the book in the pending set; retryPending flushes it on recovery") {
            withSqlDatabase {
                runTest {
                    val lib = tempLibraryDir()
                    val writer = writer(backgroundScope, lib)
                    sql.seedTestBook(bookId = "book1", rootRelPath = "MyBook")
                    // A FILE at the book-dir path makes the broker's directory-create fail typed.
                    val blocker = Path(lib, "MyBook")
                    SystemFileSystem.sink(blocker).buffered().use { it.write(byteArrayOf(1)) }

                    writer.markDirty("book1")
                    advanceTimeBy(WINDOW_MS + 1)
                    writer.awaitQuiescent()

                    SidecarWriteStateRepository(sql).findByBookId("book1").shouldBeNull()
                    writer.pendingBookIds() shouldContainExactly setOf("book1")

                    // Recovery: remove the blocking file, retry.
                    SystemFileSystem.delete(blocker)
                    writer.retryPending()

                    SystemFileSystem.exists(Path(lib, "MyBook", "listenup.json")) shouldBe true
                    SidecarWriteStateRepository(sql).findByBookId("book1").shouldNotBeNull()
                    writer.pendingBookIds().shouldBeEmpty()
                }
            }
        }
    })

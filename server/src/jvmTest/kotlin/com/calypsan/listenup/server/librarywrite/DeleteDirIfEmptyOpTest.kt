package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * [WriteOp.DeleteDirIfEmpty] — the organizer's Task 4 needs to clean up a book's source
 * directory after moving every file out of it, without risking data loss if the enumeration
 * missed something. Phase-1 regression coverage for the new op, alongside the existing
 * [LibraryWriteBrokerManifestTest] suite.
 */
class DeleteDirIfEmptyOpTest :
    FunSpec({
        test("deletes an empty directory") {
            runTest {
                val libraryDir = tempLibraryDir()
                val bookDir = Path(libraryDir, "book-1")
                SystemFileSystem.createDirectories(bookDir)

                val broker = testBroker()
                val result =
                    broker.executeManifest(
                        WriteManifest(opId = "delete-empty-1", ops = listOf(WriteOp.DeleteDirIfEmpty(bookDir))),
                    )

                result shouldBe AppResult.Success(Unit)
                SystemFileSystem.exists(bookDir) shouldBe false
            }
        }

        test("is a no-op success when the directory is already gone") {
            runTest {
                val libraryDir = tempLibraryDir()
                val bookDir = Path(libraryDir, "never-existed")

                val broker = testBroker()
                val result =
                    broker.executeManifest(
                        WriteManifest(opId = "delete-empty-2", ops = listOf(WriteOp.DeleteDirIfEmpty(bookDir))),
                    )

                result shouldBe AppResult.Success(Unit)
            }
        }

        test("leaves a non-empty directory in place rather than force-deleting its contents") {
            runTest {
                val libraryDir = tempLibraryDir()
                val bookDir = Path(libraryDir, "book-2")
                SystemFileSystem.createDirectories(bookDir)
                val strayFile = Path(bookDir, "unexpected.txt")
                SystemFileSystem.sink(strayFile).close()

                val broker = testBroker()
                val result =
                    broker.executeManifest(
                        WriteManifest(opId = "delete-empty-3", ops = listOf(WriteOp.DeleteDirIfEmpty(bookDir))),
                    )

                result shouldBe AppResult.Success(Unit)
                SystemFileSystem.exists(bookDir) shouldBe true
                SystemFileSystem.exists(strayFile) shouldBe true
            }
        }

        test("round-trips through the journal when a crash interrupts an earlier op") {
            runTest {
                val libraryDir = tempLibraryDir()
                val journalDir = tempJournalDir()
                val bookDir = Path(libraryDir, "book-3")
                SystemFileSystem.createDirectories(bookDir)
                val file = Path(bookDir, "01.m4b")
                SystemFileSystem.sink(file).close()
                val newDir = Path(libraryDir, "book-3-new")
                val newFile = Path(newDir, "01.m4b")

                val manifest =
                    WriteManifest(
                        opId = "delete-empty-4",
                        ops =
                            listOf(
                                WriteOp.EnsureDir(newDir),
                                WriteOp.MoveFile(file, newFile),
                                WriteOp.DeleteDirIfEmpty(bookDir),
                            ),
                    )

                // Simulate a crash after op 0 (EnsureDir) completed but before the move ran: persist
                // the manifest, apply op 0 directly (bypassing the broker, as the pre-crash process
                // would have), and mark it done — never call executeManifest so ops 1-2 never ran.
                val crashedJournal = WriteJournal(journalDir)
                crashedJournal.persist(manifest)
                SystemFileSystem.createDirectories(newDir)
                crashedJournal.markOpDone("delete-empty-4", 0)

                // Fresh broker + journal over the same directory, as a real restart would construct.
                val recoveredJournal = WriteJournal(journalDir)
                val broker = testBroker(journal = recoveredJournal)
                broker.recoverJournal()

                SystemFileSystem.exists(newFile) shouldBe true
                SystemFileSystem.exists(bookDir) shouldBe false
            }
        }
    })

package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

class LibraryWriteBrokerManifestTest :
    FunSpec({
        test("manifest executes all ops in order and removes its journal") {
            runTest {
                val dir = tempLibraryDir()
                val journalDir = tempJournalDir()
                val journal = WriteJournal(journalDir)
                val broker = testBroker(journal = journal)
                val bookDir = Path(dir, "Book")
                val sidecar = Path(bookDir, "listenup.json")
                val renamed = Path(bookDir, "listenup.json.bak")
                val bytes = "{}".encodeToByteArray()

                val manifest =
                    WriteManifest(
                        opId = "manifest-1",
                        ops =
                            listOf(
                                WriteOp.EnsureDir(bookDir),
                                WriteOp.WriteFile(sidecar, bytes),
                                WriteOp.MoveFile(sidecar, renamed),
                            ),
                    )

                val result = broker.executeManifest(manifest)

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                SystemFileSystem.exists(renamed) shouldBe true
                SystemFileSystem.exists(sidecar) shouldBe false
                journal.listPending() shouldBe emptyList()
            }
        }

        test("every path touched by a manifest is registered for suppression") {
            runTest {
                val dir = tempLibraryDir()
                val registry = SelfWriteRegistry { 0L }
                val broker = testBroker(registry = registry)
                val target = Path(dir, "listenup.json")

                val manifest =
                    WriteManifest(
                        opId = "manifest-2",
                        ops = listOf(WriteOp.WriteFile(target, byteArrayOf(1))),
                    )
                broker.executeManifest(manifest).shouldBeInstanceOf<AppResult.Success<Unit>>()

                registry.isSelfWrite(target) shouldBe true
            }
        }

        test("manifest against an unavailable root fails typed and leaves the journal for retry") {
            if (!isPosix()) return@test
            runTest {
                val dir = tempLibraryDir()
                makeReadOnly(dir)
                val journal = WriteJournal(tempJournalDir())
                val broker = testBroker(journal = journal)
                val target = Path(dir, "listenup.json")

                val manifest =
                    WriteManifest(
                        opId = "manifest-3",
                        ops = listOf(WriteOp.WriteFile(target, byteArrayOf(1))),
                    )

                val result = broker.executeManifest(manifest)

                result.shouldBeInstanceOf<AppResult.Failure>()
                journal.listPending().map { it.manifest.opId } shouldBe listOf("manifest-3")
            }
        }

        test("interrupted manifest resumes idempotently via recoverJournal") {
            runTest {
                val dir = tempLibraryDir()
                val journalDir = tempJournalDir()
                val firstTarget = Path(dir, "a.json")
                val secondTarget = Path(dir, "b.json")
                val manifest =
                    WriteManifest(
                        opId = "manifest-4",
                        ops =
                            listOf(
                                WriteOp.WriteFile(firstTarget, byteArrayOf(1)),
                                WriteOp.WriteFile(secondTarget, byteArrayOf(2)),
                            ),
                    )

                // Simulate a crash after op 0 completed: persist the manifest, apply op 0's
                // filesystem effect directly (bypassing the broker, as the pre-crash process
                // would have already done), and mark it done in the journal — but never call
                // executeManifest, so op 1 never ran and the journal was never deleted.
                val crashedJournal = WriteJournal(journalDir)
                crashedJournal.persist(manifest)
                SystemFileSystem.sink(firstTarget).buffered().use { it.write(byteArrayOf(1)) }
                crashedJournal.markOpDone("manifest-4", 0)

                // Fresh broker + journal instance over the same journal directory, as a real
                // restart would construct — recovery must finish op 1 and clean up the journal.
                val recoveredJournal = WriteJournal(journalDir)
                val broker = testBroker(journal = recoveredJournal)

                broker.recoverJournal()

                SystemFileSystem.source(secondTarget).buffered().use { it.readByteArray() } shouldBe byteArrayOf(2)
                recoveredJournal.listPending() shouldBe emptyList()

                // Re-running recovery with nothing left in the journal is a no-op.
                broker.recoverJournal()
                recoveredJournal.listPending() shouldBe emptyList()
            }
        }

        test("corrupt journal file is skipped and left on disk; valid manifests still recover") {
            runTest {
                val dir = tempLibraryDir()
                val journalDir = tempJournalDir()
                val corruptFile = Path(journalDir, "corrupt-op.json")
                SystemFileSystem.createDirectories(journalDir)
                SystemFileSystem.sink(corruptFile).buffered().use { it.write("{not json!".encodeToByteArray()) }

                val target = Path(dir, "recovered.json")
                WriteJournal(journalDir).persist(
                    WriteManifest(
                        opId = "valid-op",
                        ops = listOf(WriteOp.WriteFile(target, byteArrayOf(7))),
                    ),
                )

                val broker = testBroker(journal = WriteJournal(journalDir))
                broker.recoverJournal() // must not throw

                // The valid manifest recovered fully and its journal entry is gone.
                SystemFileSystem.source(target).buffered().use { it.readByteArray() } shouldBe byteArrayOf(7)
                // The corrupt file stays on disk for inspection and no longer aborts anything.
                SystemFileSystem.exists(corruptFile) shouldBe true
                WriteJournal(journalDir).listPending() shouldBe emptyList()
            }
        }

    })

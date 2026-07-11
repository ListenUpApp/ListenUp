package com.calypsan.listenup.server.librarywrite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class WriteJournalTest :
    FunSpec({
        test("persist writes a journal entry with all ops undone, staging WriteFile bytes to a data file") {
            runTest {
                val journalDir = tempJournalDir()
                val journal = WriteJournal(journalDir)
                val target = Path(journalDir, "book-listenup.json")
                val bytes = "hello".encodeToByteArray()
                val manifest =
                    WriteManifest(
                        opId = "op-1",
                        ops =
                            listOf(
                                WriteOp.EnsureDir(Path(journalDir, "book")),
                                WriteOp.WriteFile(target, bytes),
                            ),
                    )

                journal.persist(manifest)

                val pending = journal.listPending()
                pending.map { it.manifest.opId } shouldContainExactly listOf("op-1")
                val reloaded = pending.single()
                reloaded.doneFlags shouldBe listOf(false, false)
                val writeOp = reloaded.manifest.ops[1] as WriteOp.WriteFile
                writeOp.target shouldBe target
                writeOp.bytes.contentEquals(bytes) shouldBe true
            }
        }

        test("markOpDone flips only the targeted op's done flag") {
            runTest {
                val journalDir = tempJournalDir()
                val journal = WriteJournal(journalDir)
                val manifest =
                    WriteManifest(
                        opId = "op-2",
                        ops =
                            listOf(
                                WriteOp.EnsureDir(Path(journalDir, "a")),
                                WriteOp.EnsureDir(Path(journalDir, "b")),
                            ),
                    )
                journal.persist(manifest)

                journal.markOpDone("op-2", 0)

                journal.listPending().single().doneFlags shouldBe listOf(true, false)
            }
        }

        test("delete removes the journal file and its staged data") {
            runTest {
                val journalDir = tempJournalDir()
                val journal = WriteJournal(journalDir)
                val manifest =
                    WriteManifest(
                        opId = "op-3",
                        ops = listOf(WriteOp.WriteFile(Path(journalDir, "x.json"), byteArrayOf(1, 2, 3))),
                    )
                journal.persist(manifest)

                journal.delete("op-3")

                journal.listPending() shouldBe emptyList()
                SystemFileSystem.exists(Path(journalDir, "op-3.data")) shouldBe false
            }
        }

        test("listPending is empty when the journal directory doesn't exist yet") {
            runTest {
                val journalDir = Path(tempJournalDir(), "nonexistent")
                WriteJournal(journalDir).listPending() shouldBe emptyList()
            }
        }
    })

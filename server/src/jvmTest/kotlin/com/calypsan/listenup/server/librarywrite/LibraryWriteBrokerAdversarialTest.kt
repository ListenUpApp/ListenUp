package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Adversarial coverage for [LibraryWriteBroker]'s destructive paths — the cases where a bug
 * destroys bytes that aren't ours and no backup we control can bring them back.
 *
 * Every test here asserts **observable state on disk or in the journal**, never merely a return
 * type: which bytes survived, which paths exist, what the journal still holds for the next boot.
 * Containment against paths that escape the library root lives in
 * [LibraryWriteBrokerContainmentTest] — those are currently a known, disabled gap.
 */
class LibraryWriteBrokerAdversarialTest :
    FunSpec({

        // ---------------------------------------------------------------------------------
        // Unwritable root — typed degradation with nothing half-written and no stale claim.
        // ---------------------------------------------------------------------------------

        // Skipped (reported as ignored, never as a silent pass) off POSIX, and as uid 0 — root
        // writes straight through a 0555 directory, so the fixture would prove nothing there.
        test("write to an unwritable root leaves no target, no temp litter, and no stale self-write claim")
            .config(enabled = isPosix() && !isRootUser()) {
                runTest {
                    val dir = tempLibraryDir()
                    makeReadOnly(dir)
                    val registry = SelfWriteRegistry { 0L }
                    val broker = testBroker(registry = registry)
                    val target = Path(dir, "listenup.json")

                    val result = broker.writeFile(target, "{}".encodeToByteArray())

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<LibraryWriteError.Unavailable>()
                    withClue("a refused write must not leave a partially-visible target") {
                        SystemFileSystem.exists(target) shouldBe false
                    }
                    withClue("the staging temp file must never survive a failed write") {
                        tempLitterIn(dir).shouldBeEmpty()
                    }
                    // The sharp edge: a leaked claim would make the watcher swallow the user's OWN
                    // later edit to this path as if it were ours, silently losing their change.
                    withClue("the self-write claim must be released when no write landed") {
                        registry.isSelfWrite(target) shouldBe false
                    }
                }
            }

        // ---------------------------------------------------------------------------------
        // DeleteFile pointed at a directory — the case that would destroy a user's book folder.
        // ---------------------------------------------------------------------------------

        test("DeleteFile must not destroy a book directory that holds only a hidden file") {
            runTest {
                val dir = tempLibraryDir()
                val bookDir = Path(dir, "Author - Title")
                val hidden = Path(bookDir, ".DS_Store")
                writeExternally(hidden, "finder junk".encodeToByteArray())
                val journal = WriteJournal(tempJournalDir())
                val broker = testBroker(journal = journal)

                val result =
                    broker.executeManifest(
                        WriteManifest(opId = "delete-nonempty-dir", ops = listOf(WriteOp.DeleteFile(bookDir))),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<LibraryWriteError.Unavailable>()
                withClue("a directory holding ANY entry — hidden ones included — must survive") {
                    SystemFileSystem.exists(bookDir) shouldBe true
                    SystemFileSystem.exists(hidden) shouldBe true
                    bytesAt(hidden) shouldBe "finder junk".encodeToByteArray()
                }
                withClue("the failed manifest stays journalled for inspection/retry") {
                    journal.listPending().map { it.manifest.opId } shouldContainExactly listOf("delete-nonempty-dir")
                }
            }
        }

        test("DeleteFile must not destroy a book directory that holds a real audio file") {
            runTest {
                val dir = tempLibraryDir()
                val bookDir = Path(dir, "Author - Title")
                val track = Path(bookDir, "01 - Chapter One.mp3")
                writeExternally(track, AUDIO_BYTES)
                val broker = testBroker()

                val result =
                    broker.executeManifest(
                        WriteManifest(opId = "delete-book-dir", ops = listOf(WriteOp.DeleteFile(bookDir))),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                bytesAt(track) shouldBe AUDIO_BYTES
            }
        }

        // Pins a sharp edge rather than endorsing it: WriteOp.DeleteFile is documented as deleting
        // a *file*, but it hands the path straight to SystemFileSystem.delete, which on the JVM is
        // File.delete() — and that happily removes an EMPTY directory. A caller that passes a book
        // folder it believes to be a file will silently unmake it. The blast radius is capped (a
        // non-empty directory is refused, proven above), so this is documentation, not an alarm —
        // but if DeleteFile should reject directories outright, this test is the one to flip.
        test("DeleteFile against an EMPTY directory removes the directory — documented, not endorsed") {
            runTest {
                val dir = tempLibraryDir()
                val emptyBookDir = Path(dir, "Author - Emptied Title")
                SystemFileSystem.createDirectories(emptyBookDir)
                val broker = testBroker()

                val result =
                    broker.executeManifest(
                        WriteManifest(opId = "delete-empty-dir", ops = listOf(WriteOp.DeleteFile(emptyBookDir))),
                    )

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                SystemFileSystem.exists(emptyBookDir) shouldBe false
            }
        }

        test("writeFile whose parent path is an existing FILE fails typed and does not destroy that file") {
            runTest {
                val dir = tempLibraryDir()
                val collision = Path(dir, "Author - Title")
                writeExternally(collision, AUDIO_BYTES)
                val broker = testBroker()

                // The caller believes "Author - Title" is a book directory; on disk it's a file.
                val result = broker.writeFile(Path(collision, "listenup.json"), MANIFEST_BYTES)

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<LibraryWriteError.Unavailable>()
                withClue("the user's file must survive a caller's mistaken assumption about its type") {
                    bytesAt(collision) shouldBe AUDIO_BYTES
                }
                tempLitterIn(dir).shouldBeEmpty()
            }
        }

        // ---------------------------------------------------------------------------------
        // Crash-resume: the boot path, where recoverJournal runs unprompted.
        // ---------------------------------------------------------------------------------

        test("a move that landed on disk before the crash is not replayed by recoverJournal") {
            runTest {
                val dir = tempLibraryDir()
                val journalDir = tempJournalDir()
                val fromDir = Path(dir, "Inbox")
                val toDir = Path(dir, "Author - Title")
                val from = Path(fromDir, "track.m4b")
                val to = Path(toDir, "track.m4b")
                writeExternally(from, AUDIO_BYTES)
                SystemFileSystem.createDirectories(toDir)

                val manifest =
                    WriteManifest(
                        opId = "crash-after-move",
                        ops = listOf(WriteOp.EnsureDir(toDir), WriteOp.MoveFile(from, to)),
                    )

                // The nastiest crash window: the filesystem effect of op 1 completed, but the
                // process died before markOpDone recorded it. The journal therefore still claims
                // op 1 is pending, and boot recovery will attempt it again.
                val crashed = WriteJournal(journalDir)
                crashed.persist(manifest)
                crashed.markOpDone("crash-after-move", 0)
                SystemFileSystem.atomicMove(from, to)

                val recoveredJournal = WriteJournal(journalDir)
                testBroker(journal = recoveredJournal).recoverJournal()

                withClue("the already-moved file must be recognised as done, not lost or duplicated") {
                    SystemFileSystem.exists(from) shouldBe false
                    bytesAt(to) shouldBe AUDIO_BYTES
                }
                recoveredJournal.listPending().shouldBeEmpty()

                // recoverJournal runs at every boot — a second pass must change nothing.
                testBroker(journal = WriteJournal(journalDir)).recoverJournal()
                bytesAt(to) shouldBe AUDIO_BYTES
                SystemFileSystem.exists(from) shouldBe false
            }
        }

        test("one unrecoverable manifest does not starve the other pending manifests at boot") {
            runTest {
                val dir = tempLibraryDir()
                val journalDir = tempJournalDir()
                val journal = WriteJournal(journalDir)
                val good = Path(dir, "recovered.json")

                // A manifest whose move can never complete: both endpoints are gone.
                journal.persist(
                    WriteManifest(
                        opId = "aaa-doomed",
                        ops = listOf(WriteOp.MoveFile(Path(dir, "gone.m4b"), Path(dir, "never.m4b"))),
                    ),
                )
                journal.persist(
                    WriteManifest(opId = "zzz-good", ops = listOf(WriteOp.WriteFile(good, AUDIO_BYTES))),
                )

                testBroker(journal = WriteJournal(journalDir)).recoverJournal()

                withClue("the healthy manifest must still complete, whatever order recovery walked in") {
                    bytesAt(good) shouldBe AUDIO_BYTES
                }
                withClue("only the doomed manifest may remain, for the next boot to retry") {
                    WriteJournal(journalDir).listPending().map { it.manifest.opId } shouldContainExactly
                        listOf("aaa-doomed")
                }
            }
        }

        test("a source that vanishes mid-manifest fails typed, keeps earlier ops, and never replays them") {
            runTest {
                val dir = tempLibraryDir()
                val journalDir = tempJournalDir()
                val journal = WriteJournal(journalDir)
                val broker = testBroker(journal = journal)
                val sidecar = Path(dir, "listenup.json")
                val movedFrom = Path(dir, "cover-src.jpg")
                val movedTo = Path(dir, "cover.jpg")

                // op 1's source does not exist — it vanished between planning and apply.
                val result =
                    broker.executeManifest(
                        WriteManifest(
                            opId = "vanished-source",
                            ops =
                                listOf(
                                    WriteOp.WriteFile(sidecar, MANIFEST_BYTES),
                                    WriteOp.MoveFile(movedFrom, movedTo),
                                ),
                        ),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                withClue("op 0's effect is real and must stay — the broker stops, it does not roll back") {
                    bytesAt(sidecar) shouldBe MANIFEST_BYTES
                }
                withClue("op 0 is recorded done so a retry cannot re-apply it") {
                    journal.listPending().single().doneFlags shouldBe listOf(true, false)
                }
                withClue("a failed op must leave no partial destination behind") {
                    SystemFileSystem.exists(movedTo) shouldBe false
                }

                // Now prove the done flag is honoured with observable bytes: a user edits the
                // sidecar we already wrote, and op 1's source reappears. Recovery must finish
                // op 1 WITHOUT clobbering the user's edit by re-running op 0.
                writeExternally(sidecar, USER_EDIT_BYTES)
                writeExternally(movedFrom, AUDIO_BYTES)

                testBroker(journal = WriteJournal(journalDir)).recoverJournal()

                withClue("re-running a done WriteFile op would have reverted the user's edit") {
                    bytesAt(sidecar) shouldBe USER_EDIT_BYTES
                }
                bytesAt(movedTo) shouldBe AUDIO_BYTES
                WriteJournal(journalDir).listPending().shouldBeEmpty()
            }
        }

        // ---------------------------------------------------------------------------------
        // Concurrency — two manifests racing over the same bytes.
        // ---------------------------------------------------------------------------------

        test("two concurrent manifests moving the same file leave it at exactly one destination") {
            runBlocking {
                val dir = tempLibraryDir()
                val journalDir = tempJournalDir()
                val journal = WriteJournal(journalDir)
                val broker = testBroker(journal = journal)
                val source = Path(dir, "track.m4b")
                val destA = Path(Path(dir, "Author A - Title"), "track.m4b")
                val destB = Path(Path(dir, "Author B - Title"), "track.m4b")
                writeExternally(source, AUDIO_BYTES)

                fun manifestFor(
                    opId: String,
                    dest: Path,
                ) = WriteManifest(
                    opId = opId,
                    ops = listOf(WriteOp.EnsureDir(dest.parent!!), WriteOp.MoveFile(source, dest)),
                )

                val (resultA, resultB) =
                    withContext(Dispatchers.Default) {
                        listOf(
                            async { broker.executeManifest(manifestFor("race-a", destA)) },
                            async { broker.executeManifest(manifestFor("race-b", destB)) },
                        ).awaitAll()
                    }

                val landed = listOf(destA, destB).filter { SystemFileSystem.exists(it) }
                withClue("the file must exist at exactly one destination — never both, never neither") {
                    landed.size shouldBe 1
                }
                withClue("the surviving copy must be byte-identical — a torn move is data loss") {
                    bytesAt(landed.single()) shouldBe AUDIO_BYTES
                }
                withClue("the source must not linger as a duplicate of the moved file") {
                    SystemFileSystem.exists(source) shouldBe false
                }
                withClue("exactly one manifest may claim success; the loser must fail typed") {
                    listOf(resultA, resultB).count { it is AppResult.Success } shouldBe 1
                }
                val loser = if (resultA is AppResult.Failure) "race-a" else "race-b"
                withClue("only the loser's manifest stays journalled for inspection") {
                    journal.listPending().map { it.manifest.opId } shouldContainExactly listOf(loser)
                }
            }
        }

        test("two concurrent writes to the same target land one whole payload, never a blend") {
            runBlocking {
                val dir = tempLibraryDir()
                val broker = testBroker()
                val target = Path(dir, "listenup.json")
                val first = ByteArray(WIDE_PAYLOAD) { 1 }
                val second = ByteArray(WIDE_PAYLOAD) { 2 }

                withContext(Dispatchers.Default) {
                    listOf(
                        async { broker.writeFile(target, first) },
                        async { broker.writeFile(target, second) },
                    ).awaitAll()
                }

                withClue("temp-file staging plus rename must make the write all-or-nothing") {
                    val landed = bytesAt(target)
                    (landed.contentEquals(first) || landed.contentEquals(second)) shouldBe true
                }
                withClue("neither racer may abandon its staging file inside the library folder") {
                    tempLitterIn(dir).shouldBeEmpty()
                }
            }
        }
    })

private val AUDIO_BYTES = "ID3audio-payload".encodeToByteArray()
private val MANIFEST_BYTES = """{"schemaVersion":1}""".encodeToByteArray()
private val USER_EDIT_BYTES = """{"schemaVersion":1,"editedByHuman":true}""".encodeToByteArray()
private const val WIDE_PAYLOAD = 64 * 1024

package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.div

/**
 * [WriteOp.DeleteDir] — the recursive delete behind Delete Book, and the most destructive op in
 * the system.
 *
 * Every refusal test here asserts **the files are still on disk**, never merely that a
 * [AppResult.Failure] came back. A guard that returns an error *after* unlinking is worse than no
 * guard at all, and only the filesystem can tell the two apart.
 *
 * Each escape test hands the broker a **narrow** root (the fixture's own `library/`) rather than
 * the fixture default: the escape targets live in a sibling temp directory, and under the broad
 * default allow-list these would pass while proving nothing. The first test is the control that
 * keeps the rest honest — it proves the same broker, same fixture, deletes a legitimate book
 * directory happily, so a refusal below can only be a guard talking and not a missing path.
 */
class DeleteDirOpTest :
    FunSpec({

        /** A parent holding a `library/` root and a sibling `outside/` standing in for the rest of the disk. */
        fun escapeFixture(): Pair<Path, Path> {
            val base = Files.createTempDirectory("delete-dir-op-")
            val root = (base / "library").apply { createDirectories() }
            val outside = (base / "outside").apply { createDirectories() }
            return Path(root.toString()) to Path(outside.toString())
        }

        test("CONTROL — deletes a book directory, its files, and its subdirectories") {
            runTest {
                val (root, _) = escapeFixture()
                val bookDir = Path(root, "Author", "A Book")
                SystemFileSystem.createDirectories(Path(bookDir, "extras"))
                writeExternally(Path(bookDir, "01.m4b"), AUDIO)
                writeExternally(Path(bookDir, "bonus.pdf"), PDF)
                writeExternally(Path(bookDir, "cover.jpg"), COVER)
                writeExternally(Path(Path(bookDir, "extras"), "map.pdf"), PDF)

                val result =
                    testBroker(roots = listOf(root)).executeManifest(
                        WriteManifest(opId = "delete-dir-control", ops = listOf(WriteOp.DeleteDir(bookDir))),
                    )

                result shouldBe AppResult.Success(Unit)
                withClue("the folder goes as a unit — non-audio files included") {
                    SystemFileSystem.exists(bookDir) shouldBe false
                }
                withClue("the parent author folder is NOT the target and must survive") {
                    SystemFileSystem.exists(Path(root, "Author")) shouldBe true
                }
            }
        }

        test("is a no-op success when the directory is already gone") {
            runTest {
                val (root, _) = escapeFixture()
                val result =
                    testBroker(roots = listOf(root)).executeManifest(
                        WriteManifest(
                            opId = "delete-dir-absent",
                            ops = listOf(WriteOp.DeleteDir(Path(root, "never-existed"))),
                        ),
                    )

                result shouldBe AppResult.Success(Unit)
            }
        }

        test("refuses to delete a library folder root, and the books inside it survive") {
            runTest {
                val (root, _) = escapeFixture()
                val bookDir = Path(root, "A Book")
                SystemFileSystem.createDirectories(bookDir)
                writeExternally(Path(bookDir, "01.m4b"), AUDIO)

                val result =
                    testBroker(roots = listOf(root)).executeManifest(
                        WriteManifest(opId = "delete-dir-root", ops = listOf(WriteOp.DeleteDir(root))),
                    )

                withClue("containment cannot catch this — a root is trivially inside itself") {
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<LibraryWriteError.ProtectedPath>()
                }
                withClue("the library and everything in it must be untouched") {
                    SystemFileSystem.exists(root) shouldBe true
                    bytesAt(Path(bookDir, "01.m4b")) shouldBe AUDIO
                }
            }
        }

        test("refuses a directory that escapes the library root via .., and the folder outside survives") {
            runTest {
                val (root, outside) = escapeFixture()
                // The intermediate segment must really exist, or the kernel refuses the path for its
                // own reasons (ENOENT) and the broker LOOKS contained when it is not — a false green.
                SystemFileSystem.createDirectories(Path(root, "Book"))
                val victimDir = Path(outside, "Documents")
                SystemFileSystem.createDirectories(victimDir)
                writeExternally(Path(victimDir, "taxes.pdf"), USER_DATA)

                val escaping = Path(root, "Book", "..", "..", "outside", "Documents")
                val result =
                    testBroker(roots = listOf(root)).executeManifest(
                        WriteManifest(opId = "delete-dir-traversal", ops = listOf(WriteOp.DeleteDir(escaping))),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                withClue("the user's directory outside the library must be byte-for-byte untouched") {
                    SystemFileSystem.exists(victimDir) shouldBe true
                    bytesAt(Path(victimDir, "taxes.pdf")) shouldBe USER_DATA
                }
            }
        }

        test("refuses a symlinked book directory, and the real directory behind the link survives") {
            if (!isPosix()) return@test
            runTest {
                val (root, outside) = escapeFixture()
                val realBook =
                    java.nio.file.Path
                        .of(outside.toString(), "RealBook")
                        .apply { createDirectories() }
                writeExternally(Path(realBook.toString(), "01.m4b"), USER_DATA)
                // <root>/Book is a link out of the library — a plausible way a user shares one folder
                // into two libraries, and a way for a recursive delete to leave the library entirely.
                Files.createSymbolicLink(
                    java.nio.file.Path
                        .of(root.toString(), "Book"),
                    realBook,
                )

                val result =
                    testBroker(roots = listOf(root)).executeManifest(
                        WriteManifest(
                            opId = "delete-dir-symlink",
                            ops = listOf(WriteOp.DeleteDir(Path(root, "Book"))),
                        ),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                withClue("nothing behind the link may be removed") {
                    SystemFileSystem.exists(Path(realBook.toString())) shouldBe true
                    bytesAt(Path(realBook.toString(), "01.m4b")) shouldBe USER_DATA
                }
            }
        }

        test("refuses a symlinked book directory even when the link points INSIDE the library") {
            if (!isPosix()) return@test
            runTest {
                val (root, _) = escapeFixture()
                val realBook =
                    java.nio.file.Path
                        .of(root.toString(), "RealBook")
                        .apply { createDirectories() }
                writeExternally(Path(realBook.toString(), "01.m4b"), AUDIO)
                Files.createSymbolicLink(
                    java.nio.file.Path
                        .of(root.toString(), "Alias"),
                    realBook,
                )

                val result =
                    testBroker(roots = listOf(root)).executeManifest(
                        WriteManifest(
                            opId = "delete-dir-inside-symlink",
                            ops = listOf(WriteOp.DeleteDir(Path(root, "Alias"))),
                        ),
                    )

                withClue("containment PASSES here (the link resolves inside the library) — only the symlink refusal saves the real book") {
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<LibraryWriteError.ProtectedPath>()
                }
                withClue("the real book the alias pointed at must be intact") {
                    bytesAt(Path(realBook.toString(), "01.m4b")) shouldBe AUDIO
                }
            }
        }

        test("never follows a symlink found INSIDE the deleted directory") {
            if (!isPosix()) return@test
            runTest {
                val (root, outside) = escapeFixture()
                val victimDir =
                    java.nio.file.Path
                        .of(outside.toString(), "Photos")
                        .apply { createDirectories() }
                writeExternally(Path(victimDir.toString(), "wedding.jpg"), USER_DATA)

                val bookDir = Path(root, "A Book")
                SystemFileSystem.createDirectories(bookDir)
                writeExternally(Path(bookDir, "01.m4b"), AUDIO)
                // Someone linked their photo folder into the book folder. Deleting the book must
                // unlink the link, never walk through it.
                Files.createSymbolicLink(
                    java.nio.file.Path
                        .of(bookDir.toString(), "photos"),
                    victimDir,
                )

                val result =
                    testBroker(roots = listOf(root)).executeManifest(
                        WriteManifest(opId = "delete-dir-inner-link", ops = listOf(WriteOp.DeleteDir(bookDir))),
                    )

                result shouldBe AppResult.Success(Unit)
                SystemFileSystem.exists(bookDir) shouldBe false
                withClue("the linked-to directory is not part of the book and must survive intact") {
                    Files.isDirectory(victimDir) shouldBe true
                    bytesAt(Path(victimDir.toString(), "wedding.jpg")) shouldBe USER_DATA
                }
            }
        }

        test("resumes through the journal when a crash interrupts the manifest before the delete") {
            runTest {
                val (root, _) = escapeFixture()
                val journalDir = tempJournalDir()
                val bookDir = Path(root, "A Book")
                SystemFileSystem.createDirectories(bookDir)
                writeExternally(Path(bookDir, "01.m4b"), AUDIO)
                writeExternally(Path(bookDir, "bonus.pdf"), PDF)
                val marker = Path(root, "marker")

                val manifest =
                    WriteManifest(
                        opId = "delete-dir-resume",
                        ops = listOf(WriteOp.EnsureDir(marker), WriteOp.DeleteDir(bookDir)),
                    )

                // Simulate a crash after op 0 landed but before op 1 ran: persist, apply op 0 by
                // hand as the pre-crash process would have, mark it done, never call executeManifest.
                val crashedJournal = WriteJournal(journalDir)
                crashedJournal.persist(manifest)
                SystemFileSystem.createDirectories(marker)
                crashedJournal.markOpDone("delete-dir-resume", 0)

                // Fresh broker + journal over the same directory, as a real restart would construct.
                testBroker(journal = WriteJournal(journalDir), roots = listOf(root)).recoverJournal()

                withClue("an interrupted delete must finish on the next boot, not linger half-done") {
                    SystemFileSystem.exists(bookDir) shouldBe false
                }
                withClue("and the journal entry is drained once the manifest completes") {
                    WriteJournal(journalDir).listPending() shouldBe emptyList()
                }
            }
        }
        test("refuses a directory that CONTAINS a library folder root") {
            runTest {
                val (root, _) = escapeFixture()
                // A second library folder configured deeper in the same tree — nothing forbids
                // nested roots, and a not-yet-scanned one holds no book rows for BookDeleter's
                // own guard to find.
                val nestedRoot = Path(root, "Author", "Omnibus")
                SystemFileSystem.createDirectories(nestedRoot)
                writeExternally(Path(nestedRoot, "01.m4b"), AUDIO)
                val target = Path(root, "Author")

                val result =
                    testBroker(roots = listOf(root, nestedRoot)).executeManifest(
                        WriteManifest(opId = "delete-dir-contains-root", ops = listOf(WriteOp.DeleteDir(target))),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<LibraryWriteError.ProtectedPath>()
                withClue("erasing a library folder is the one mistake that takes the library with it") {
                    SystemFileSystem.exists(Path(nestedRoot, "01.m4b")) shouldBe true
                    SystemFileSystem.exists(target) shouldBe true
                }
            }
        }
        test("a REPORTED failure on a no-resume manifest leaves nothing for a later boot to replay") {
            runTest {
                val (root, _) = escapeFixture()
                val journal = WriteJournal(tempJournalDir())
                // A DeleteDir aimed at a library root: guaranteed to fail the guard, having
                // touched nothing — the same shape as a delete refused by a transient fault.
                val result =
                    testBroker(roots = listOf(root), journal = journal).executeManifest(
                        WriteManifest(
                            opId = "delete-book-reported-failure",
                            ops = listOf(WriteOp.DeleteDir(root)),
                            resumeAfterReportedFailure = false,
                        ),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                withClue("the caller was told it failed; a reboot must not re-decide for them") {
                    journal.listPending().shouldBeEmpty()
                }
            }
        }

        test("a REPORTED failure on a normal manifest stays journalled, as organize moves need") {
            runTest {
                val (root, _) = escapeFixture()
                val journal = WriteJournal(tempJournalDir())

                val result =
                    testBroker(roots = listOf(root), journal = journal).executeManifest(
                        // Same guaranteed failure, default resume semantics.
                        WriteManifest(opId = "organize-move-keeps-entry", ops = listOf(WriteOp.DeleteDir(root))),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                withClue("a half-finished move must still be finishable at the next boot") {
                    journal.listPending().map { it.manifest.opId } shouldBe listOf("organize-move-keeps-entry")
                }
            }
        }
    })

private val AUDIO = "AUDIO BYTES".encodeToByteArray()
private val PDF = "PDF BYTES".encodeToByteArray()
private val COVER = "COVER BYTES".encodeToByteArray()
private val USER_DATA = "USER DATA — NOT OURS".encodeToByteArray()

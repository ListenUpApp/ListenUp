package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.api.result.AppResult
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.div

/**
 * The broker refuses to write or delete outside the library, however the path is dressed up.
 *
 * [LibraryWriteBroker] is "the sole writer inside library folders" — these tests pin the *inside*
 * half. Two escapes are covered because they fail differently: `..` segments, which a lexical
 * normalisation folds away, and a symlinked book directory, which only resolving the path against
 * the filesystem can catch. A raw string prefix test would accept both.
 *
 * Every test hands the broker a **narrow** root (the fixture's own `library/`) rather than the
 * fixture default. That matters: the escape target lives in a sibling temp directory, so under a
 * broad allow-list these would pass while proving nothing.
 *
 * The first test is the control, and it is not decoration. Each escape test asserts a *failure*,
 * and failures are cheap — a missing directory, a typo'd path or a closed handle all produce one.
 * The control proves the same broker with the same fixture writes happily to a legitimate path, so
 * a refusal below can only be the containment guard talking. (This spec's `..` test once passed
 * against a broker with no containment at all, because the intermediate directory did not exist
 * and the kernel returned ENOENT. Hence the explicit `createDirectories` in it.)
 */
class LibraryWriteBrokerContainmentTest :
    FunSpec({

        /** A parent holding a `library/` root and a sibling `outside/` that stands in for the rest of the disk. */
        fun escapeFixture(): Triple<Path, Path, Path> {
            val base = Files.createTempDirectory("library-write-containment-")
            val root = (base / "library").apply { createDirectories() }
            val outside = (base / "outside").apply { createDirectories() }
            val victim = Path(outside.toString(), "precious.txt")
            writeExternally(victim, USER_DATA)
            return Triple(Path(root.toString()), Path(outside.toString()), victim)
        }

        test("CONTROL — the same broker, same fixture, writes happily INSIDE the library root") {
            runTest {
                val (root, _, _) = escapeFixture()
                val broker = testBroker(roots = listOf(root))
                SystemFileSystem.createDirectories(Path(root, "Book"))
                val legitimate = Path(Path(root, "Book"), "listenup.json")

                val result = broker.writeFile(legitimate, CLOBBER)

                withClue("containment must not refuse a legitimate in-library write") {
                    result.shouldBeInstanceOf<AppResult.Success<WrittenFile>>()
                }
                withClue("and the bytes must actually land") {
                    bytesAt(legitimate) shouldBe CLOBBER
                }
            }
        }

        test("writeFile must refuse a target that escapes the library root via ..") {
            runTest {
                val (root, _, victim) = escapeFixture()
                val broker = testBroker(roots = listOf(root))
                // The book directory must really exist. With a missing intermediate segment the
                // kernel rejects the path for its own reasons (ENOENT) and the broker *looks*
                // contained when it is not — a false green. A real library folder has real book
                // directories in it, so build one.
                SystemFileSystem.createDirectories(Path(root, "Book"))
                // <root>/Book/../../outside/precious.txt — resolves to the user's file outside the library.
                val escaping = Path(root, "Book", "..", "..", "outside", "precious.txt")

                val result = broker.writeFile(escaping, CLOBBER)

                withClue("a target resolving outside the library root must be refused") {
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
                withClue("the file outside the library must be byte-for-byte untouched") {
                    bytesAt(victim) shouldBe USER_DATA
                }
            }
        }

        test("a manifest WriteFile op must refuse a target that escapes the library root via ..") {
            runTest {
                val (root, _, victim) = escapeFixture()
                val journal = WriteJournal(tempJournalDir())
                val broker = testBroker(journal = journal, roots = listOf(root))
                val escaping = Path(root, "..", "outside", "precious.txt")

                val result =
                    broker.executeManifest(
                        WriteManifest(opId = "escape-write", ops = listOf(WriteOp.WriteFile(escaping, CLOBBER))),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                bytesAt(victim) shouldBe USER_DATA
            }
        }

        test("a manifest DeleteFile op must refuse a target that escapes the library root via ..") {
            runTest {
                val (root, _, victim) = escapeFixture()
                val broker = testBroker(roots = listOf(root))
                val escaping = Path(root, "..", "outside", "precious.txt")

                val result =
                    broker.executeManifest(
                        WriteManifest(opId = "escape-delete", ops = listOf(WriteOp.DeleteFile(escaping))),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                withClue("a delete resolving outside the library must not destroy the user's file") {
                    SystemFileSystem.exists(victim) shouldBe true
                    bytesAt(victim) shouldBe USER_DATA
                }
            }
        }

        test("a manifest DeleteDirIfEmpty op must refuse a directory that escapes the library root via ..") {
            runTest {
                val (root, outside, _) = escapeFixture()
                val broker = testBroker(roots = listOf(root))
                // Deliberately EMPTY. A non-empty directory survives via the op's own
                // leave-it-alone branch, which would make this pass with no containment at all —
                // the same false green the `..` write test's KDoc warns about. Empty means only
                // the guard can save it.
                val strandedDir = Path(outside.toString(), "EmptyBookDir")
                SystemFileSystem.createDirectories(strandedDir)
                val escaping = Path(root, "..", "outside", "EmptyBookDir")

                val result =
                    broker.executeManifest(
                        WriteManifest(opId = "escape-rmdir", ops = listOf(WriteOp.DeleteDirIfEmpty(escaping))),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                withClue("a directory delete resolving outside the library must not remove the user's folder") {
                    SystemFileSystem.exists(strandedDir) shouldBe true
                }
            }
        }

        test("writeFile must not follow a symlinked book directory out of the library root") {
            if (!isPosix()) return@test
            runTest {
                val (root, outside, _) = escapeFixture()
                val realBook =
                    java.nio.file.Path
                        .of(outside.toString(), "RealBook")
                        .apply { createDirectories() }
                // <root>/Book is a symlink to <outside>/RealBook — a plausible way a user shares
                // one folder into two libraries, and a way for our write to leave the library.
                Files.createSymbolicLink(
                    java.nio.file.Path
                        .of(root.toString(), "Book"),
                    realBook,
                )
                val broker = testBroker(roots = listOf(root))
                val throughLink = Path(Path(root, "Book"), "listenup.json")

                val result = broker.writeFile(throughLink, CLOBBER)

                result.shouldBeInstanceOf<AppResult.Failure>()
                withClue("nothing may land in the symlink's real target, which is outside the library") {
                    SystemFileSystem.exists(Path(realBook.toString(), "listenup.json")) shouldBe false
                }
            }
        }

        test("a manifest DeleteFile op must not follow a symlinked book directory out of the library root") {
            if (!isPosix()) return@test
            runTest {
                val (root, outside, _) = escapeFixture()
                val realBook =
                    java.nio.file.Path
                        .of(outside.toString(), "RealBook")
                        .apply { createDirectories() }
                val userFile = Path(realBook.toString(), "cover.jpg")
                writeExternally(userFile, USER_DATA)
                Files.createSymbolicLink(
                    java.nio.file.Path
                        .of(root.toString(), "Book"),
                    realBook,
                )
                val broker = testBroker(roots = listOf(root))

                val result =
                    broker.executeManifest(
                        WriteManifest(
                            opId = "escape-symlink-delete",
                            ops = listOf(WriteOp.DeleteFile(Path(Path(root, "Book"), "cover.jpg"))),
                        ),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                withClue("the user's file behind the symlink must survive") {
                    SystemFileSystem.exists(userFile) shouldBe true
                    bytesAt(userFile) shouldBe USER_DATA
                }
            }
        }
    })

private val USER_DATA = "USER DATA — NOT OURS".encodeToByteArray()
private val CLOBBER = "CLOBBERED BY THE BROKER".encodeToByteArray()

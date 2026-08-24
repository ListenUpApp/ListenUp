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
 * ⚠️ KNOWN GAP — every test in this spec is **disabled (`xtest`)**, not because the assertion is
 * wrong, but because [LibraryWriteBroker] does not currently implement the behaviour it asserts.
 *
 * **The finding:** the broker has no notion of a library root. [LibraryWriteBroker.writeFile] and
 * every [WriteOp] take a bare absolute [Path] and hand it straight to `SystemFileSystem`; the only
 * method that ever sees a root at all is [LibraryWriteBroker.probe], which merely reads it. So a
 * caller that computes a destination from untrusted input — a book title, a filename from an
 * upload, a metadata field — can address any path the server process can reach, and the broker
 * will faithfully write or delete there. `..` segments are never normalised away, and a symlinked
 * book directory is followed out of the library like any other directory.
 *
 * **Verified, not assumed.** Enabled locally on 2026-08-24, all five fail: `writeFile` returns
 * `Success(WrittenFile(<root>/Book/../../outside/precious.txt, …))` having overwritten a file that
 * is not in the library at all, and a `DeleteFile` op returns `Success` having deleted one.
 *
 * These tests assert the **safe** behaviour (refuse, and leave the outside file untouched) so that
 * the day containment lands they flip from `xtest` to `test` and immediately pass. They are
 * deliberately NOT rewritten to match today's behaviour — pinning an escape as "expected" would
 * make the gap permanent.
 *
 * Note that [com.calypsan.listenup.server.io.isUnder] already exists in the codebase as a
 * containment primitive, but it is a raw string prefix check on un-normalised paths — it would
 * return `true` for `<root>/../outside/x`, so it is not on its own sufficient here. Containment
 * needs real path resolution (`toRealPath`-equivalent) against a root the broker is given.
 *
 * Escalate to the human before enabling: adding a root to the broker's constructor or to every
 * op is an API change with call-site ripple, and picking the failure shape (a new
 * `LibraryWriteError.OutsideLibrary` vs. reusing `Unavailable`) is a design decision.
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

        xtest("writeFile must refuse a target that escapes the library root via ..") {
            runTest {
                val (root, _, victim) = escapeFixture()
                val broker = testBroker()
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

        xtest("a manifest WriteFile op must refuse a target that escapes the library root via ..") {
            runTest {
                val (root, _, victim) = escapeFixture()
                val journal = WriteJournal(tempJournalDir())
                val broker = testBroker(journal = journal)
                val escaping = Path(root, "..", "outside", "precious.txt")

                val result =
                    broker.executeManifest(
                        WriteManifest(opId = "escape-write", ops = listOf(WriteOp.WriteFile(escaping, CLOBBER))),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                bytesAt(victim) shouldBe USER_DATA
            }
        }

        xtest("a manifest DeleteFile op must refuse a target that escapes the library root via ..") {
            runTest {
                val (root, _, victim) = escapeFixture()
                val broker = testBroker()
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

        xtest("writeFile must not follow a symlinked book directory out of the library root") {
            if (!isPosix()) return@xtest
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
                val broker = testBroker()
                val throughLink = Path(Path(root, "Book"), "listenup.json")

                val result = broker.writeFile(throughLink, CLOBBER)

                result.shouldBeInstanceOf<AppResult.Failure>()
                withClue("nothing may land in the symlink's real target, which is outside the library") {
                    SystemFileSystem.exists(Path(realBook.toString(), "listenup.json")) shouldBe false
                }
            }
        }

        xtest("a manifest DeleteFile op must not follow a symlinked book directory out of the library root") {
            if (!isPosix()) return@xtest
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
                val broker = testBroker()

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

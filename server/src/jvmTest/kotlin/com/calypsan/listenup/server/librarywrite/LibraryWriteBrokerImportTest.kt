package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * [WriteOp.ImportFile]'s own contract — the one op whose source deliberately lives *outside* every
 * library folder, so it cannot inherit [WriteOp.MoveFile]'s two-sided containment check.
 *
 * The tests that matter are the refusals. An op that relaxes containment on one side has to prove
 * exactly what it kept on the other, or "the broker is the sole library writer" quietly becomes
 * "the broker will move anything anywhere if you ask via the new op".
 */
class LibraryWriteBrokerImportTest :
    FunSpec({

        fun tempStagingDir(): Path = Path(Files.createTempDirectory("upload-staging-").toString())

        test("imports a staged file into the library and removes it from staging") {
            val library = tempLibraryDir()
            val staging = tempStagingDir()
            writeExternally(Path(staging, "book", "01.m4b"), "audio".encodeToByteArray())
            val broker = testBroker(roots = listOf(library))

            val result =
                runBlocking {
                    broker.executeManifest(
                        WriteManifest(
                            opId = "import-ok",
                            ops =
                                listOf(
                                    WriteOp.EnsureDir(Path(library, "Author", "Title")),
                                    WriteOp.ImportFile(
                                        from = Path(staging, "book", "01.m4b"),
                                        to = Path(library, "Author", "Title", "01.m4b"),
                                        fromRoot = staging,
                                    ),
                                ),
                        ),
                    )
                }

            result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            bytesAt(Path(library, "Author", "Title", "01.m4b")).decodeToString() shouldBe "audio"
            SystemFileSystem.exists(Path(staging, "book", "01.m4b")) shouldBe false
            tempLitterIn(Path(library, "Author", "Title")) shouldBe emptyList()
        }

        test("re-running a completed import is a no-op, not an error — the crash-resume rule") {
            val library = tempLibraryDir()
            val staging = tempStagingDir()
            writeExternally(Path(library, "Title", "01.m4b"), "already here".encodeToByteArray())
            val broker = testBroker(roots = listOf(library))

            val result =
                runBlocking {
                    broker.executeManifest(
                        WriteManifest(
                            opId = "import-resume",
                            ops =
                                listOf(
                                    WriteOp.ImportFile(
                                        from = Path(staging, "01.m4b"), // already moved: gone from staging
                                        to = Path(library, "Title", "01.m4b"),
                                        fromRoot = staging,
                                    ),
                                ),
                        ),
                    )
                }

            result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            bytesAt(Path(library, "Title", "01.m4b")).decodeToString() shouldBe "already here"
        }

        test("a destination outside every library folder is refused") {
            val library = tempLibraryDir()
            val staging = tempStagingDir()
            val elsewhere = tempStagingDir()
            writeExternally(Path(staging, "01.m4b"), "audio".encodeToByteArray())
            val broker = testBroker(roots = listOf(library))

            val result =
                runBlocking {
                    broker.executeManifest(
                        WriteManifest(
                            opId = "import-bad-dest",
                            ops =
                                listOf(
                                    WriteOp.ImportFile(
                                        from = Path(staging, "01.m4b"),
                                        to = Path(elsewhere, "01.m4b"),
                                        fromRoot = staging,
                                    ),
                                ),
                        ),
                    )
                }

            result
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<LibraryWriteError.OutsideLibrary>()
            SystemFileSystem.exists(Path(elsewhere, "01.m4b")) shouldBe false
        }

        test("a source that does not resolve inside its declared staging root is refused") {
            val library = tempLibraryDir()
            val staging = tempStagingDir()
            val other = tempStagingDir()
            writeExternally(Path(other, "secret.m4b"), "not yours".encodeToByteArray())
            val broker = testBroker(roots = listOf(library))

            val result =
                runBlocking {
                    broker.executeManifest(
                        WriteManifest(
                            opId = "import-escaped-source",
                            ops =
                                listOf(
                                    WriteOp.ImportFile(
                                        from = Path(other, "secret.m4b"),
                                        to = Path(library, "secret.m4b"),
                                        fromRoot = staging,
                                    ),
                                ),
                        ),
                    )
                }

            result
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<LibraryWriteError.OutsideLibrary>()
            SystemFileSystem.exists(Path(library, "secret.m4b")) shouldBe false
            SystemFileSystem.exists(Path(other, "secret.m4b")) shouldBe true
        }

        test("a `..` source that only lexically sits under its staging root is refused") {
            val library = tempLibraryDir()
            val staging = tempStagingDir()
            val outside = tempStagingDir()
            writeExternally(Path(outside, "secret.m4b"), "not yours".encodeToByteArray())
            val broker = testBroker(roots = listOf(library))

            val result =
                runBlocking {
                    broker.executeManifest(
                        WriteManifest(
                            opId = "import-dotdot-source",
                            ops =
                                listOf(
                                    WriteOp.ImportFile(
                                        from = Path("$staging/../${outside.name}/secret.m4b"),
                                        to = Path(library, "secret.m4b"),
                                        fromRoot = staging,
                                    ),
                                ),
                        ),
                    )
                }

            result
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<LibraryWriteError.OutsideLibrary>()
            SystemFileSystem.exists(Path(library, "secret.m4b")) shouldBe false
        }

        test("a staging root that is itself inside a library folder is refused — ImportFile is not a back door to MoveFile") {
            val library = tempLibraryDir()
            val insideLibrary = Path(library, "pretend-staging")
            writeExternally(Path(insideLibrary, "01.m4b"), "library content".encodeToByteArray())
            val broker = testBroker(roots = listOf(library))

            val result =
                runBlocking {
                    broker.executeManifest(
                        WriteManifest(
                            opId = "import-inside-library",
                            ops =
                                listOf(
                                    WriteOp.ImportFile(
                                        from = Path(insideLibrary, "01.m4b"),
                                        to = Path(library, "moved.m4b"),
                                        fromRoot = insideLibrary,
                                    ),
                                ),
                        ),
                    )
                }

            result
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<LibraryWriteError.OutsideLibrary>()
            SystemFileSystem.exists(Path(library, "moved.m4b")) shouldBe false
        }

        test("an import survives a journal round trip with its staging root intact") {
            val journalDir = tempJournalDir()
            val journal = WriteJournal(journalDir)
            val manifest =
                WriteManifest(
                    opId = "import-journal",
                    ops =
                        listOf(
                            WriteOp.ImportFile(
                                from = Path("/staging/s1/01.m4b"),
                                to = Path("/library/Author/Title/01.m4b"),
                                fromRoot = Path("/staging/s1"),
                            ),
                        ),
                )

            val pending =
                runBlocking {
                    journal.persist(manifest)
                    journal.listPending()
                }

            val op =
                pending
                    .single()
                    .manifest.ops
                    .single()
                    .shouldBeInstanceOf<WriteOp.ImportFile>()
            op.from.toString() shouldBe "/staging/s1/01.m4b"
            op.to.toString() shouldBe "/library/Author/Title/01.m4b"
            op.fromRoot.toString() shouldBe "/staging/s1"
            pending.single().doneFlags shouldBe listOf(false)
        }
    })

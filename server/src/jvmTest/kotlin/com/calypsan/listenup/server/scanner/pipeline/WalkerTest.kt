package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.server.scanner.audioLibrary
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.files.Path as IoPath

class WalkerTest :
    FunSpec({

        val walker = Walker()

        test("emits every file in a flat directory") {
            audioLibrary {
                audio("track-01.mp3")
                audio("track-02.mp3")
                image("cover.jpg")
            }.use { fixture ->
                runTest {
                    val entries = walker.walk(IoPath(fixture.root.toString())).toList()
                    entries.map { it.relPath } shouldContainExactlyInAnyOrder
                        listOf("track-01.mp3", "track-02.mp3", "cover.jpg")
                }
            }
        }

        test("descends into nested directories and uses POSIX separators in relPath") {
            audioLibrary {
                book("Author/Series/Title") {
                    tracks(count = 2)
                    cover()
                }
            }.use { fixture ->
                runTest {
                    val entries = walker.walk(IoPath(fixture.root.toString())).toList()
                    val paths = entries.map { it.relPath }
                    paths shouldContainAll
                        listOf(
                            "Author/Series/Title/01 - Track.mp3",
                            "Author/Series/Title/02 - Track.mp3",
                            "Author/Series/Title/cover.jpg",
                        )
                    withClue("relPath must use POSIX separators on every OS") {
                        paths.forEach { it shouldBe it.replace('\\', '/') }
                    }
                }
            }
        }

        test("skips dotfiles, temp extensions, and .ignore-marked subtrees") {
            audioLibrary {
                audio("real-track.mp3")
                raw(".DS_Store") // dotfile
                audio("download.mp3.part") // temp ext
                ignore("HiddenAuthor") // creates HiddenAuthor/.ignore
                audio("HiddenAuthor/buried.mp3") // should be skipped
            }.use { fixture ->
                runTest {
                    val entries = walker.walk(IoPath(fixture.root.toString())).toList()
                    val paths = entries.map { it.relPath }

                    paths shouldContain "real-track.mp3"
                    paths shouldNotContain ".DS_Store"
                    paths shouldNotContain "download.mp3.part"
                    paths shouldNotContain "HiddenAuthor/buried.mp3"
                }
            }
        }

        test("prunes an entire subtree several levels below a .ignore marker") {
            audioLibrary {
                audio("Top/keep.mp3")
                ignore("Top/Hidden") // creates Top/Hidden/.ignore
                audio("Top/Hidden/Deep/Deeper/buried.mp3") // pruned at Hidden's child
            }.use { fixture ->
                runTest {
                    val paths = walker.walk(IoPath(fixture.root.toString())).toList().map { it.relPath }
                    paths shouldContain "Top/keep.mp3"
                    withClue("nothing under a .ignore-marked directory may surface") {
                        paths.none { it.startsWith("Top/Hidden/") } shouldBe true
                    }
                }
            }
        }

        test("never descends a linked directory and does not emit it") {
            audioLibrary {
                audio("real-track.mp3")
            }.use { fixture ->
                // Create a symlink loop: <root>/loop -> <root>
                val loop = fixture.root.resolve("loop")
                runCatching { Files.createSymbolicLink(loop, fixture.root) }
                    .getOrElse { return@use }
                runTest {
                    val paths = walker.walk(IoPath(fixture.root.toString())).toList().map { it.relPath }
                    paths shouldContain "real-track.mp3"
                    paths.none { it == "loop" || it.startsWith("loop/") } shouldBe true
                }
            }
        }

        test("skips a link that points outside the library") {
            audioLibrary {
                audio("real-track.mp3")
            }.use { fixture ->
                val outside = Files.createTempDirectory("listenup-outside-")
                try {
                    val target = outside.resolve("secret.txt")
                    Files.writeString(target, "secret")
                    runCatching { Files.createSymbolicLink(fixture.root.resolve("escape.pdf"), target) }
                        .getOrElse { return@use }
                    runTest {
                        val paths = walker.walk(IoPath(fixture.root.toString())).toList().map { it.relPath }
                        paths shouldNotContain "escape.pdf"
                        paths shouldContain "real-track.mp3"
                    }
                } finally {
                    outside.toFile().deleteRecursively()
                }
            }
        }

        test("emits a link that points at a file inside the library") {
            audioLibrary {
                audio("real-track.mp3")
            }.use { fixture ->
                runCatching {
                    Files.createSymbolicLink(fixture.root.resolve("alias.mp3"), fixture.root.resolve("real-track.mp3"))
                }.getOrElse { return@use }
                runTest {
                    val paths = walker.walk(IoPath(fixture.root.toString())).toList().map { it.relPath }
                    paths shouldContainExactlyInAnyOrder listOf("real-track.mp3", "alias.mp3")
                }
            }
        }

        test("skips a FIFO") {
            audioLibrary {
                audio("real-track.mp3")
            }.use { fixture ->
                val fifo = fixture.root.resolve("pipe.mp3")
                val made = runCatching { ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() }.getOrDefault(-1)
                if (made != 0) return@use
                runTest(timeout = 10.seconds) {
                    val paths = walker.walk(IoPath(fixture.root.toString())).toList().map { it.relPath }
                    paths shouldNotContain "pipe.mp3"
                    paths shouldContain "real-track.mp3"
                }
            }
        }

        test("captures inode on POSIX filesystems") {
            audioLibrary {
                audio("track.mp3")
            }.use { fixture ->
                runTest {
                    val entry = walker.walk(IoPath(fixture.root.toString())).toList().single()
                    if (System.getProperty("os.name").lowercase().contains("windows")) {
                        entry.inode.shouldBeNull()
                    } else {
                        entry.inode.shouldNotBeNull()
                    }
                }
            }
        }

        test("classifies files by extension") {
            audioLibrary {
                audio("track.mp3")
                image("cover.png")
                raw("metadata.json", contents = "{}")
                raw("desc.txt", contents = "")
                raw("README", contents = "")
            }.use { fixture ->
                runTest {
                    val byName = walker.walk(IoPath(fixture.root.toString())).toList().associateBy { it.name }
                    byName.getValue("track.mp3").fileType shouldBe FileType.AUDIO
                    byName.getValue("cover.png").fileType shouldBe FileType.IMAGE
                    byName.getValue("metadata.json").fileType shouldBe FileType.METADATA
                    byName.getValue("desc.txt").fileType shouldBe FileType.TEXT
                    byName.getValue("README").fileType shouldBe FileType.UNKNOWN
                }
            }
        }

        test("returns empty flow when root is not a directory") {
            runTest {
                val notADir: Path = Files.createTempFile("listenup-not-a-dir-", ".txt")
                try {
                    walker.walk(IoPath(notADir.toString())).toList() shouldBe emptyList()
                } finally {
                    Files.deleteIfExists(notADir)
                }
            }
        }

        test("captures size and mtime") {
            audioLibrary {
                audio("track.mp3", sizeBytes = 1024L)
            }.use { fixture ->
                runTest {
                    val entry = walker.walk(IoPath(fixture.root.toString())).toList().single()
                    entry.size shouldBe 1024L
                    (entry.mtimeMs > 0L) shouldBe true
                }
            }
        }
    })

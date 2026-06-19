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
                    val entries = walker.walk(fixture.root).toList()
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
                    val entries = walker.walk(fixture.root).toList()
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
                    val entries = walker.walk(fixture.root).toList()
                    val paths = entries.map { it.relPath }

                    paths shouldContain "real-track.mp3"
                    paths shouldNotContain ".DS_Store"
                    paths shouldNotContain "download.mp3.part"
                    paths shouldNotContain "HiddenAuthor/buried.mp3"
                }
            }
        }

        test("does not follow symlinks (cycles cannot crash the walk)") {
            audioLibrary {
                audio("real-track.mp3")
            }.use { fixture ->
                // Create a symlink loop: <root>/loop -> <root>
                val loop = fixture.root.resolve("loop")
                runCatching { Files.createSymbolicLink(loop, fixture.root) }
                    .getOrElse { return@use }
                runTest {
                    val entries = walker.walk(fixture.root).toList()
                    entries.map { it.relPath } shouldContain "real-track.mp3"
                    // The symlink itself isn't followed; if visitFile reports it,
                    // it'd appear as `loop` with no extension — UNKNOWN type.
                    entries.none { it.relPath.startsWith("loop/") } shouldBe true
                }
            }
        }

        test("captures inode on POSIX filesystems") {
            audioLibrary {
                audio("track.mp3")
            }.use { fixture ->
                runTest {
                    val entry = walker.walk(fixture.root).toList().single()
                    if (System.getProperty("os.name").lowercase().contains("windows")) {
                        // Windows default filesystem doesn't expose fileKey.
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
                    val byName = walker.walk(fixture.root).toList().associateBy { it.name }
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
                    walker.walk(notADir).toList() shouldBe emptyList()
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
                    val entry = walker.walk(fixture.root).toList().single()
                    entry.size shouldBe 1024L
                    (entry.mtimeMs > 0L) shouldBe true
                }
            }
        }
    })

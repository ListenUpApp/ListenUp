package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class GrouperTest :
    FunSpec({

        val grouper = Grouper()

        test("groups files in a single book directory") {
            runTest {
                val files =
                    listOf(
                        entry("Author/Title/01.mp3", FileType.AUDIO),
                        entry("Author/Title/02.mp3", FileType.AUDIO),
                        entry("Author/Title/cover.jpg", FileType.IMAGE),
                    )
                val books = grouper.group(files.asFlow()).toList()
                books shouldContainExactly
                    listOf(
                        CandidateBook(
                            rootRelPath = "Author/Title",
                            isFile = false,
                            files = files,
                            discFolders = emptyList(),
                        ),
                    )
            }
        }

        test("groups files in author/series/title layout") {
            runTest {
                val files =
                    listOf(
                        entry("Sanderson/Stormlight/Way of Kings/01.mp3", FileType.AUDIO),
                        entry("Sanderson/Stormlight/Way of Kings/02.mp3", FileType.AUDIO),
                    )
                val book = grouper.group(files.asFlow()).toList().single()
                book.rootRelPath shouldBe "Sanderson/Stormlight/Way of Kings"
                book.files shouldContainExactly files
            }
        }

        test("collapses multi-disc subdirectories into the parent book") {
            runTest {
                val files =
                    listOf(
                        entry("Author/Title/CD1/01.mp3", FileType.AUDIO),
                        entry("Author/Title/CD1/02.mp3", FileType.AUDIO),
                        entry("Author/Title/CD2/01.mp3", FileType.AUDIO),
                        entry("Author/Title/cover.jpg", FileType.IMAGE),
                    )
                val book = grouper.group(files.asFlow()).toList().single()
                book.rootRelPath shouldBe "Author/Title"
                book.files shouldContainExactlyInAnyOrder files
                book.discFolders shouldContainExactly listOf("CD1", "CD2")
            }
        }

        test("recognises CD/Disc/Disk variants case-insensitively") {
            runTest {
                val files =
                    listOf(
                        entry("Title/cd1/01.mp3", FileType.AUDIO),
                        entry("Title/Disc 2/01.mp3", FileType.AUDIO),
                        entry("Title/disk 3/01.mp3", FileType.AUDIO),
                        entry("Title/DISC 99/01.mp3", FileType.AUDIO),
                    )
                val book = grouper.group(files.asFlow()).toList().single()
                book.rootRelPath shouldBe "Title"
                book.discFolders shouldContainExactly listOf("DISC 99", "Disc 2", "cd1", "disk 3")
            }
        }

        test("does NOT roll up folders that look disc-like but exceed the digit cap") {
            runTest {
                // 4-digit numeric — outside ABS's 1-3 digit window. Treat as a
                // regular folder (book root = the "CD1234" folder itself).
                val files = listOf(entry("Author/CD1234/01.mp3", FileType.AUDIO))
                val book = grouper.group(files.asFlow()).toList().single()
                book.rootRelPath shouldBe "Author/CD1234"
                book.discFolders shouldBe emptyList()
            }
        }

        test("emits a single-file book when audio sits at the library root") {
            runTest {
                val files = listOf(entry("standalone.m4b", FileType.AUDIO))
                val book = grouper.group(files.asFlow()).toList().single()
                book.rootRelPath shouldBe "standalone.m4b"
                book.isFile shouldBe true
                book.files shouldContainExactly files
            }
        }

        test("emits multiple single-file books when several audio files sit at root") {
            runTest {
                val files =
                    listOf(
                        entry("a.m4b", FileType.AUDIO),
                        entry("b.mp3", FileType.AUDIO),
                    )
                val books = grouper.group(files.asFlow()).toList()
                books.size shouldBe 2
                books.all { it.isFile } shouldBe true
                books.map { it.rootRelPath } shouldContainExactlyInAnyOrder listOf("a.m4b", "b.mp3")
            }
        }

        test("rolls an audio-less subfolder up into the nearest ancestor book that has audio") {
            runTest {
                // Author/Title/extras/ holds only a PDF — it is not a book. Its files must attach to
                // the owning audio book instead of becoming an audio-less "book" (NoRecognizedAudio).
                val files =
                    listOf(
                        entry("Author/Title/01.mp3", FileType.AUDIO),
                        entry("Author/Title/extras/notes.pdf", FileType.EBOOK),
                    )
                val books = grouper.group(files.asFlow()).toList()
                books.map { it.rootRelPath } shouldContainExactly listOf("Author/Title")
                books.single().files.map { it.relPath } shouldContainExactlyInAnyOrder
                    listOf("Author/Title/01.mp3", "Author/Title/extras/notes.pdf")
            }
        }

        test("audio-less rollup does not regress multi-disc collapse") {
            runTest {
                val files =
                    listOf(
                        entry("Title/CD1/01.mp3", FileType.AUDIO),
                        entry("Title/CD2/01.mp3", FileType.AUDIO),
                        entry("Title/extras/booklet.pdf", FileType.EBOOK),
                    )
                val book = grouper.group(files.asFlow()).toList().single()
                book.rootRelPath shouldBe "Title"
                book.discFolders shouldContainExactly listOf("CD1", "CD2")
                book.files.map { it.relPath } shouldContain "Title/extras/booklet.pdf"
            }
        }

        test("keeps an audio-less folder as its own candidate when no audio ancestor exists") {
            runTest {
                // No audio anywhere → nothing to roll up into. Preserve the folder as a candidate
                // (the Analyzer surfaces it) rather than silently dropping it.
                val files = listOf(entry("Author/ArtOnly/scan.pdf", FileType.EBOOK))
                val book = grouper.group(files.asFlow()).toList().single()
                book.rootRelPath shouldBe "Author/ArtOnly"
            }
        }

        test("a root-level single-file book keeps a sibling cover image") {
            runTest {
                val files =
                    listOf(
                        entry("MyBook.m4b", FileType.AUDIO),
                        entry("cover.jpg", FileType.IMAGE),
                    )
                val book = grouper.group(files.asFlow()).toList().single()
                book.rootRelPath shouldBe "MyBook.m4b"
                book.isFile shouldBe true
                book.files.map { it.relPath } shouldContainExactlyInAnyOrder listOf("MyBook.m4b", "cover.jpg")
            }
        }

        test("drops non-audio files at the library root (orphans)") {
            runTest {
                val files =
                    listOf(
                        entry("orphan.jpg", FileType.IMAGE),
                        entry("orphan.json", FileType.METADATA),
                    )
                grouper.group(files.asFlow()).toList() shouldBe emptyList()
            }
        }

        test("emits two books for two adjacent title folders under one author") {
            runTest {
                val files =
                    listOf(
                        entry("Author/Title-A/01.mp3", FileType.AUDIO),
                        entry("Author/Title-B/01.mp3", FileType.AUDIO),
                    )
                val books = grouper.group(files.asFlow()).toList()
                books.map { it.rootRelPath } shouldContainExactly listOf("Author/Title-A", "Author/Title-B")
            }
        }

        test("preserves first-seen order of book roots") {
            runTest {
                val files =
                    listOf(
                        entry("Z-author/01.mp3", FileType.AUDIO),
                        entry("A-author/01.mp3", FileType.AUDIO),
                        entry("M-author/01.mp3", FileType.AUDIO),
                    )
                val books = grouper.group(files.asFlow()).toList()
                books.map { it.rootRelPath } shouldContainExactly listOf("Z-author", "A-author", "M-author")
            }
        }

        test("collects audio + image + metadata files into one book") {
            runTest {
                val files =
                    listOf(
                        entry("Title/01.mp3", FileType.AUDIO),
                        entry("Title/cover.jpg", FileType.IMAGE),
                        entry("Title/metadata.json", FileType.METADATA),
                        entry("Title/desc.txt", FileType.TEXT),
                    )
                val book = grouper.group(files.asFlow()).toList().single()
                book.files shouldContainExactly files
            }
        }
    })

private fun entry(
    relPath: String,
    fileType: FileType,
): FileEntry =
    FileEntry(
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        ext = relPath.substringAfterLast('.', "").lowercase(),
        size = 0,
        mtimeMs = 0,
        inode = null,
        fileType = fileType,
    )

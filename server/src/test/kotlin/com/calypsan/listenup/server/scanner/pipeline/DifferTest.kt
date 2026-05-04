package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class DifferTest :
    FunSpec({

        val differ = Differ()

        test("first scan emits Added for every current book") {
            runTest {
                val a = book("Author/Title-A", inode = 1)
                val b = book("Author/Title-B", inode = 2)
                val events = differ.diff(listOf(a, b).asFlow(), previous = emptyList()).toList()

                events.size shouldBe 2
                events.all { it is ChangeEventDto.Added } shouldBe true
                events.map { (it as ChangeEventDto.Added).book.candidate.rootRelPath } shouldContainExactly
                    listOf("Author/Title-A", "Author/Title-B")
            }
        }

        test("identical scans emit no events") {
            runTest {
                val a = book("Author/Title", inode = 1)
                val events = differ.diff(listOf(a).asFlow(), previous = listOf(a)).toList()
                events shouldBe emptyList()
            }
        }

        test("removed books emit Removed after current flow is exhausted") {
            runTest {
                val a = book("Author/Title-A", inode = 1)
                val b = book("Author/Title-B", inode = 2)
                val events = differ.diff(listOf(a).asFlow(), previous = listOf(a, b)).toList()

                events.size shouldBe 1
                events.single().shouldBeInstanceOf<ChangeEventDto.Removed>()
                (events.single() as ChangeEventDto.Removed).rootRelPath shouldBe "Author/Title-B"
            }
        }

        test("a content change at the same path emits Modified") {
            runTest {
                val before = book("Author/Title", inode = 1, trackCount = 3)
                val after = book("Author/Title", inode = 1, trackCount = 4)
                val events = differ.diff(listOf(after).asFlow(), previous = listOf(before)).toList()

                events.size shouldBe 1
                val mod = events.single().shouldBeInstanceOf<ChangeEventDto.Modified>()
                mod.book.candidate.rootRelPath shouldBe "Author/Title"
                mod.previousRootRelPath shouldBe "Author/Title"
                mod.book.candidate.files.size shouldBe 4
            }
        }

        test("a folder rename with stable inode emits Moved (not Removed + Added)") {
            runTest {
                val before = book("Author/Old Title", inode = 42)
                val after = book("Author/New Title", inode = 42)
                val events = differ.diff(listOf(after).asFlow(), previous = listOf(before)).toList()

                events.size shouldBe 1
                val moved = events.single().shouldBeInstanceOf<ChangeEventDto.Moved>()
                moved.from shouldBe "Author/Old Title"
                moved.to shouldBe "Author/New Title"
                moved.book.candidate.rootRelPath shouldBe "Author/New Title"
            }
        }

        test("a folder rename without inode degrades to Removed + Added") {
            runTest {
                val before = book("Author/Old Title", inode = null)
                val after = book("Author/New Title", inode = null)
                val events = differ.diff(listOf(after).asFlow(), previous = listOf(before)).toList()

                events.size shouldBe 2
                events.map { it::class.simpleName } shouldContainExactlyInAnyOrder
                    listOf("Added", "Removed")
            }
        }

        test("mixed Add + Modify + Remove in a single scan") {
            runTest {
                val unchanged = book("Author/Stays The Same", inode = 1)
                val modifiedBefore = book("Author/Gets Modified", inode = 2, trackCount = 1)
                val modifiedAfter = book("Author/Gets Modified", inode = 2, trackCount = 2)
                val removed = book("Author/Disappears", inode = 3)
                val added = book("Author/Newly Appears", inode = 4)

                val events =
                    differ
                        .diff(
                            current = listOf(unchanged, modifiedAfter, added).asFlow(),
                            previous = listOf(unchanged, modifiedBefore, removed),
                        ).toList()

                events.map { it::class.simpleName } shouldContainExactlyInAnyOrder
                    listOf("Modified", "Added", "Removed")
            }
        }

        test("inode match wins over Removed when files are present in current") {
            runTest {
                // The book moved AND added a new file. Should emit Moved (with the
                // updated content), not Removed+Added.
                val before = book("Author/Old Title", inode = 7, trackCount = 2)
                val after = book("Author/New Title", inode = 7, trackCount = 3)
                val events = differ.diff(listOf(after).asFlow(), previous = listOf(before)).toList()

                val moved = events.single().shouldBeInstanceOf<ChangeEventDto.Moved>()
                moved.from shouldBe "Author/Old Title"
                moved.to shouldBe "Author/New Title"
                moved.book.candidate.files.size shouldBe 3
            }
        }

        test("books with no audio files cannot be moved by inode") {
            runTest {
                // Only image files; no audio inode index entry.
                val before = analyzedBook("Author/A", files = listOf(image("Author/A/cover.jpg", inode = 1)))
                val after = analyzedBook("Author/B", files = listOf(image("Author/B/cover.jpg", inode = 1)))
                val events = differ.diff(listOf(after).asFlow(), previous = listOf(before)).toList()

                // Image inodes don't participate in move detection — degrades to Add+Remove.
                events.map { it::class.simpleName } shouldContainExactlyInAnyOrder
                    listOf("Added", "Removed")
            }
        }
    })

private fun book(
    rootRelPath: String,
    inode: Long?,
    trackCount: Int = 1,
): AnalyzedBook {
    val files =
        (1..trackCount).map { i ->
            FileEntry(
                relPath = "$rootRelPath/%02d.mp3".format(i),
                name = "%02d.mp3".format(i),
                ext = "mp3",
                size = 0,
                mtimeMs = 0,
                // Use the same base inode for every track in a book so the test is
                // deterministic; offsets prevent collisions between books.
                inode = inode?.let { it * 100 + i },
                fileType = FileType.AUDIO,
            )
        }
    return analyzedBook(rootRelPath, files)
}

private fun image(
    relPath: String,
    inode: Long?,
): FileEntry =
    FileEntry(
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        ext = relPath.substringAfterLast('.', "").lowercase(),
        size = 0,
        mtimeMs = 0,
        inode = inode,
        fileType = FileType.IMAGE,
    )

private fun analyzedBook(
    rootRelPath: String,
    files: List<FileEntry>,
): AnalyzedBook =
    AnalyzedBook(
        candidate =
            CandidateBook(
                rootRelPath = rootRelPath,
                isFile = false,
                files = files,
            ),
        title = rootRelPath.substringAfterLast('/'),
        tracks = emptyList(),
    )

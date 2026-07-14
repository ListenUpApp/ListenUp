package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.MetadataSource
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.dto.scanner.TrackNumberSource
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.external.abs.AbsChapter
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.api.external.abs.AbsMetadata
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

/**
 * Round-trip every scanner DTO + sealed variant through `contractJson`.
 * This is the contract regression net — any drift in polymorphic discriminator
 * or default-value handling fails here before any pipeline code runs.
 */
class ScannerDtoContractTest :
    FunSpec({

        test("FileEntry survives JSON round-trip with all fields") {
            val entry =
                FileEntry(
                    relPath = "Author/Series/Book/01 - Chapter.mp3",
                    name = "01 - Chapter.mp3",
                    ext = "mp3",
                    size = 12_345_678L,
                    mtimeMs = 1_714_694_400_000L,
                    inode = 4242L,
                    fileType = FileType.AUDIO,
                )
            roundTrip(entry) shouldBe entry
        }

        test("FileEntry tolerates a null inode (Windows / FAT / SMB)") {
            val entry =
                FileEntry(
                    relPath = "x.mp3",
                    name = "x.mp3",
                    ext = "mp3",
                    size = 0L,
                    mtimeMs = 0L,
                    inode = null,
                    fileType = FileType.AUDIO,
                )
            roundTrip(entry) shouldBe entry
        }

        test("FileType variants round-trip") {
            FileType.entries.forEach { roundTrip(it) shouldBe it }
        }

        test("SeriesEntry round-trips with string sequence (1.5, 0a allowed)") {
            roundTrip(SeriesEntry("Stormlight Archive", "1")) shouldBe SeriesEntry("Stormlight Archive", "1")
            roundTrip(SeriesEntry("Mistborn Era 2", "1.5")) shouldBe SeriesEntry("Mistborn Era 2", "1.5")
            roundTrip(SeriesEntry("Cosmere", "0a")) shouldBe SeriesEntry("Cosmere", "0a")
            roundTrip(SeriesEntry("Standalone")) shouldBe SeriesEntry("Standalone")
        }

        test("TrackEntry round-trips with all source variants") {
            val file =
                FileEntry(
                    relPath = "track.mp3",
                    name = "track.mp3",
                    ext = "mp3",
                    size = 0L,
                    mtimeMs = 0L,
                    fileType = FileType.AUDIO,
                )
            TrackNumberSource.entries.forEach { source ->
                val entry = TrackEntry(file = file, trackNumber = 1, discNumber = 1, trackSource = source, discSource = source)
                roundTrip(entry) shouldBe entry
            }
            // Nullable fields default cleanly.
            roundTrip(TrackEntry(file = file)) shouldBe TrackEntry(file = file)
        }

        test("ScanPhase + MetadataSource enum variants round-trip") {
            ScanPhase.entries.forEach { roundTrip(it) shouldBe it }
            MetadataSource.entries.forEach { roundTrip(it) shouldBe it }
        }

        test("CandidateBook with multi-disc folders round-trips") {
            val candidate =
                CandidateBook(
                    rootRelPath = "Author/Book",
                    isFile = false,
                    files =
                        listOf(
                            FileEntry("Author/Book/CD1/01.mp3", "01.mp3", "mp3", 0, 0, fileType = FileType.AUDIO),
                            FileEntry("Author/Book/CD2/01.mp3", "01.mp3", "mp3", 0, 0, fileType = FileType.AUDIO),
                        ),
                    discFolders = listOf("Author/Book/CD1", "Author/Book/CD2"),
                )
            roundTrip(candidate) shouldBe candidate
        }

        test("AnalyzedBook with rich metadata round-trips") {
            val candidate = CandidateBook("Author/Series/Book", false, emptyList())
            val book =
                AnalyzedBook(
                    candidate = candidate,
                    title = "The Way of Kings",
                    subtitle = "Book One of the Stormlight Archive",
                    authors = listOf("Brandon Sanderson"),
                    narrators = listOf("Michael Kramer", "Kate Reading"),
                    series = listOf(SeriesEntry("Stormlight Archive", "1")),
                    publishedYear = 2010,
                    asin = "B0015T963C",
                    description = "A long book.",
                    fieldProvenance =
                        mapOf(
                            BookField.TITLE to FieldProvenance(FieldSourceKind.FOLDER),
                            BookField.DESCRIPTION to FieldProvenance(FieldSourceKind.ABS_METADATA),
                        ),
                )
            roundTrip(book) shouldBe book
        }

        test("AnalyzedBook with only the required fields round-trips") {
            val minimal =
                AnalyzedBook(
                    candidate = CandidateBook("Book", true, emptyList()),
                    title = "Untitled",
                    tracks = emptyList(),
                )
            roundTrip(minimal) shouldBe minimal
        }

        test("ChangeEventDto polymorphic discriminator survives all four variants") {
            val book =
                AnalyzedBook(
                    candidate = CandidateBook("Book", false, emptyList()),
                    title = "T",
                )
            val variants =
                listOf<ChangeEventDto>(
                    ChangeEventDto.Added(book),
                    ChangeEventDto.Modified(book, "old/path"),
                    ChangeEventDto.Removed("old/path"),
                    ChangeEventDto.Moved("a/b", "c/d", book),
                )
            variants.forEach { original ->
                val json = contractJson.encodeToString<ChangeEventDto>(original)
                contractJson.decodeFromString<ChangeEventDto>(json) shouldBe original
            }
        }

        test("ScanResult + ScanResultSummary round-trip") {
            val summary =
                ScanResultSummary(
                    correlationId = "corr-1",
                    totalBooks = 42,
                    added = 10,
                    modified = 5,
                    removed = 1,
                    moved = 0,
                    errors = 0,
                    durationMs = 1234,
                    filesWalked = 200,
                )
            roundTrip(summary) shouldBe summary

            val result =
                ScanResult(
                    correlationId = "corr-1",
                    rootPath = "/library",
                    books = emptyList(),
                    changes = emptyList(),
                    errors = emptyList(),
                    durationMs = 1234,
                    filesWalked = 200,
                    filesSkipped = 5,
                )
            roundTrip(result) shouldBe result
        }

        test("ScanError variants survive round-trip via the AppError parent type") {
            val variants =
                listOf<ScanError>(
                    ScanError.AlreadyRunning(correlationId = "c"),
                    ScanError.LibraryPathNotConfigured(correlationId = "c"),
                    ScanError.LibraryPathNotFound(correlationId = "c", path = "/missing"),
                    ScanError.FileUnreadable(correlationId = "c", debugInfo = "perm denied", path = "/path"),
                    ScanError.MetadataParseError(correlationId = "c", debugInfo = "bad json", path = "/path"),
                    ScanError.TitleInferenceError(correlationId = "c", path = "/path"),
                )
            variants.forEach { original ->
                val asAppError: AppError = original
                val json = contractJson.encodeToString<AppError>(asAppError)
                contractJson.decodeFromString<AppError>(json) shouldBe original
            }
        }

        test("ScanEvent variants round-trip") {
            val libId = LibraryId("lib-1")
            val started: ScanEvent = ScanEvent.Started("c", libId, "/library")
            val progress: ScanEvent = ScanEvent.Progress("c", libId, ScanPhase.ANALYZING, 100, 10, 0)
            val change: ScanEvent =
                ScanEvent.Change(
                    "c",
                    libId,
                    ChangeEventDto.Added(
                        AnalyzedBook(CandidateBook("Book", false, emptyList()), "T"),
                    ),
                )
            val completed: ScanEvent =
                ScanEvent.Completed(
                    "c",
                    libId,
                    ScanResultSummary("c", 1, 1, 0, 0, 0, 0, 100, 10),
                )
            listOf(started, progress, change, completed).forEach {
                val json = contractJson.encodeToString<ScanEvent>(it)
                contractJson.decodeFromString<ScanEvent>(json) shouldBe it
            }
        }

        test("AbsMetadata accepts the flat schema with all optional fields absent") {
            val flat = """{"title":"Hello","authors":["Alice"]}"""
            val parsed = contractJson.decodeFromString<AbsMetadata>(flat)
            parsed.title shouldBe "Hello"
            parsed.authors shouldBe listOf("Alice")
            parsed.series shouldBe emptyList()
        }

        test("AbsMetadata round-trips with chapters") {
            val md =
                AbsMetadata(
                    title = "Hello",
                    authors = listOf("Alice"),
                    series = listOf("Series #1"),
                    chapters = listOf(AbsChapter(0, 0.0, 60.0, "Chapter 1")),
                )
            roundTrip(md) shouldBe md
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))

package com.calypsan.listenup.server.seed

/** Which sidecar metadata file a seed book ships, so the generator exercises every parser path. */
enum class SeedSidecar {
    NONE,
    METADATA_JSON,
    NFO,
    OPF,
    READER_TXT,
    DESC_TXT,
}

/** One audio track. [durationSeconds] is short (a few seconds) — the files are tiny by design. */
data class SeedTrack(
    val fileName: String,
    val durationSeconds: Int,
    val trackNumber: Int,
)

/** A chapter marker, used for single-file books that carry embedded chapters. */
data class SeedChapter(
    val title: String,
    val startSeconds: Int,
)

/** Series membership. */
data class SeedSeries(
    val name: String,
    val sequence: String,
)

/**
 * One synthetic audiobook. [folderPath] is relative to the library root and ends in the
 * book's title folder; the generator lays the bottom three path components out as
 * `<author>/<series>/<title>` so the scanner's FolderShape resolves author + series + title.
 * Multi-disc books put their tracks under `CD1/`, `CD2/` subfolders (see [discFolders]).
 */
data class SeedBook(
    val folderPath: String,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val series: SeedSeries?,
    val tracks: List<SeedTrack>,
    val chapters: List<SeedChapter>,
    val sidecar: SeedSidecar,
    val hasCover: Boolean,
    val description: String,
    val discFolders: Boolean = false,
)

/**
 * The committed synthetic library: ~10-12 books spanning single- and multi-file audiobooks,
 * a multi-book series, a spread of contributors, and deliberate edge cases (no cover, sparse
 * metadata, very long title, multi-narrator). Edit this list to change the demo library;
 * rerun `:server:generateSeedLibrary` to regenerate the files.
 */
object SeedLibraryDescriptor {
    val BOOKS: List<SeedBook> =
        buildList {
            addRepresentativeBooks()
            addEdgeCaseBooks()
        }
}

private const val AUTHOR_WREN_HALLOWAY = "Wren Halloway"
private const val NARRATOR_MARLOWE_FINCH = "Marlowe Finch"
private const val SERIES_EMBER_CODEX = "The Ember Codex"

/** Common track file names reused across many seed books. */
private const val TRACK_SINGLE_M4B = "book.m4b"
private const val TRACK_01_MP3 = "01.mp3"
private const val TRACK_02_MP3 = "02.mp3"
private const val TRACK_03_MP3 = "03.mp3"

/**
 * Core library entries: a series, single-file and multi-file standalones, and multi-disc.
 * Covers METADATA_JSON, NFO, OPF, and DESC_TXT sidecars.
 */
private fun MutableList<SeedBook>.addRepresentativeBooks() {
    // ── The Ember Codex trilogy (series, 3 books) ────────────────────────────────────────────
    // Book 1 — single-file, embedded chapters, METADATA_JSON sidecar
    add(
        SeedBook(
            folderPath = "$AUTHOR_WREN_HALLOWAY/$SERIES_EMBER_CODEX/$SERIES_EMBER_CODEX",
            title = SERIES_EMBER_CODEX,
            authors = listOf(AUTHOR_WREN_HALLOWAY),
            narrators = listOf(NARRATOR_MARLOWE_FINCH),
            series = SeedSeries(name = SERIES_EMBER_CODEX, sequence = "1"),
            tracks =
                listOf(
                    SeedTrack(fileName = TRACK_SINGLE_M4B, durationSeconds = 18, trackNumber = 1),
                ),
            chapters =
                listOf(
                    SeedChapter(title = "The First Flame", startSeconds = 0),
                    SeedChapter(title = "Ash and Ember", startSeconds = 6),
                    SeedChapter(title = "The Codex Revealed", startSeconds = 12),
                ),
            sidecar = SeedSidecar.METADATA_JSON,
            hasCover = true,
            description = "Lyra discovers a tome that should not exist — and a power she cannot name.",
        ),
    )

    // Book 2 — multi-file, no embedded chapters, NFO sidecar
    add(
        SeedBook(
            folderPath = "$AUTHOR_WREN_HALLOWAY/$SERIES_EMBER_CODEX/$SERIES_EMBER_CODEX - Shattered Sigil",
            title = "$SERIES_EMBER_CODEX: Shattered Sigil",
            authors = listOf(AUTHOR_WREN_HALLOWAY),
            narrators = listOf(NARRATOR_MARLOWE_FINCH),
            series = SeedSeries(name = SERIES_EMBER_CODEX, sequence = "2"),
            tracks =
                listOf(
                    SeedTrack(fileName = TRACK_01_MP3, durationSeconds = 5, trackNumber = 1),
                    SeedTrack(fileName = TRACK_02_MP3, durationSeconds = 6, trackNumber = 2),
                    SeedTrack(fileName = TRACK_03_MP3, durationSeconds = 5, trackNumber = 3),
                ),
            chapters = emptyList(),
            sidecar = SeedSidecar.NFO,
            hasCover = true,
            description = "The sigil fractures. The alliance splinters. Lyra stands alone.",
        ),
    )

    // Book 3 — multi-file, no embedded chapters, OPF sidecar
    add(
        SeedBook(
            folderPath = "$AUTHOR_WREN_HALLOWAY/$SERIES_EMBER_CODEX/$SERIES_EMBER_CODEX - The Final Pyre",
            title = "$SERIES_EMBER_CODEX: The Final Pyre",
            authors = listOf(AUTHOR_WREN_HALLOWAY),
            narrators = listOf(NARRATOR_MARLOWE_FINCH),
            series = SeedSeries(name = SERIES_EMBER_CODEX, sequence = "3"),
            tracks =
                listOf(
                    SeedTrack(fileName = TRACK_01_MP3, durationSeconds = 4, trackNumber = 1),
                    SeedTrack(fileName = TRACK_02_MP3, durationSeconds = 5, trackNumber = 2),
                    SeedTrack(fileName = TRACK_03_MP3, durationSeconds = 6, trackNumber = 3),
                    SeedTrack(fileName = "04.mp3", durationSeconds = 4, trackNumber = 4),
                ),
            chapters = emptyList(),
            sidecar = SeedSidecar.OPF,
            hasCover = true,
            description = "Fire must consume everything before something new can grow.",
        ),
    )

    // ── Standalone: multi-disc, DESC_TXT sidecar ────────────────────────────────────────────
    // discFolders=true: the generator places exactly one track under each CDn/ folder —
    // a deliberate synthetic-data simplification, not a faithful real-world multi-disc layout.
    add(
        SeedBook(
            folderPath = "Cassia Vane/The Clockwork Archipelago",
            title = "The Clockwork Archipelago",
            authors = listOf("Cassia Vane"),
            narrators = listOf("Idris Okafor"),
            series = null,
            tracks =
                listOf(
                    SeedTrack(fileName = TRACK_01_MP3, durationSeconds = 5, trackNumber = 1),
                    SeedTrack(fileName = TRACK_02_MP3, durationSeconds = 6, trackNumber = 2),
                    SeedTrack(fileName = TRACK_03_MP3, durationSeconds = 5, trackNumber = 3),
                    SeedTrack(fileName = "04.mp3", durationSeconds = 6, trackNumber = 4),
                    SeedTrack(fileName = "05.mp3", durationSeconds = 5, trackNumber = 5),
                ),
            chapters = emptyList(),
            sidecar = SeedSidecar.DESC_TXT,
            hasCover = true,
            description = "Islands of brass and steam. A navigator who cannot forget. A chart that rewrites itself.",
            discFolders = true,
        ),
    )

    // ── Standalone: single-file, embedded chapters, READER_TXT sidecar ─────────────────────
    add(
        SeedBook(
            folderPath = "Theo Morrow/The Glass Meridian",
            title = "The Glass Meridian",
            authors = listOf("Theo Morrow"),
            narrators = listOf("Priya Sundaram"),
            series = null,
            tracks =
                listOf(
                    SeedTrack(fileName = TRACK_SINGLE_M4B, durationSeconds = 20, trackNumber = 1),
                ),
            chapters =
                listOf(
                    SeedChapter(title = "Zero Latitude", startSeconds = 0),
                    SeedChapter(title = "The Glass Line", startSeconds = 7),
                    SeedChapter(title = "True North", startSeconds = 14),
                ),
            sidecar = SeedSidecar.READER_TXT,
            hasCover = true,
            description = "A cartographer who maps places that do not yet exist.",
        ),
    )
}

/**
 * Edge-case entries: no cover, very long title, multi-narrator, and the NONE sidecar
 * (sparse metadata — deliberately thin so the scanner exercises its fallback paths).
 */
private fun MutableList<SeedBook>.addEdgeCaseBooks() {
    // NONE sidecar + no cover — exercises sparse-metadata scanner path
    add(
        SeedBook(
            folderPath = "Aldric Penn/The Salt Roads",
            title = "The Salt Roads",
            authors = listOf("Aldric Penn"),
            narrators = listOf("Nora Vex"),
            series = null,
            tracks =
                listOf(
                    SeedTrack(fileName = TRACK_01_MP3, durationSeconds = 4, trackNumber = 1),
                    SeedTrack(fileName = TRACK_02_MP3, durationSeconds = 5, trackNumber = 2),
                    SeedTrack(fileName = TRACK_03_MP3, durationSeconds = 4, trackNumber = 3),
                ),
            chapters = emptyList(),
            sidecar = SeedSidecar.NONE,
            hasCover = false,
            description = "Trade routes, betrayal, and a merchant who knows too much.",
        ),
    )

    // Title > 80 chars — exercises long-title handling in DB and UI
    add(
        SeedBook(
            folderPath = "Isolde Crane/A Treatise on the Peculiar Navigation of Unmapped Tidal Seas",
            title = "A Treatise on the Peculiar Navigation of Unmapped Tidal Seas and Their Inhabitants",
            authors = listOf("Isolde Crane"),
            narrators = listOf("Finn Maccabe"),
            series = null,
            tracks =
                listOf(
                    SeedTrack(fileName = TRACK_SINGLE_M4B, durationSeconds = 15, trackNumber = 1),
                ),
            chapters =
                listOf(
                    SeedChapter(title = "Prologue", startSeconds = 0),
                    SeedChapter(title = "On Currents Unseen", startSeconds = 6),
                    SeedChapter(title = "Afterword", startSeconds = 11),
                ),
            sidecar = SeedSidecar.METADATA_JSON,
            hasCover = true,
            description = "A Victorian scholar's field notes on seas that refuse to be charted.",
        ),
    )

    // Multi-narrator — exercises narrator-list parsing and contributor join table
    add(
        SeedBook(
            folderPath = "Sable Orin/The Fractured Court",
            title = "The Fractured Court",
            authors = listOf("Sable Orin"),
            narrators = listOf("Lena Vask", "Dex Orin"),
            series = null,
            tracks =
                listOf(
                    SeedTrack(fileName = TRACK_01_MP3, durationSeconds = 5, trackNumber = 1),
                    SeedTrack(fileName = TRACK_02_MP3, durationSeconds = 6, trackNumber = 2),
                    SeedTrack(fileName = TRACK_03_MP3, durationSeconds = 5, trackNumber = 3),
                ),
            chapters = emptyList(),
            sidecar = SeedSidecar.NFO,
            hasCover = true,
            description = "Two narrators voice a courtroom where no verdict is safe.",
        ),
    )

    // Co-authored standalone — exercises multi-author contributor join table
    add(
        SeedBook(
            folderPath = "Remy Vaux and Dalia Stern/The Iron Horizon",
            title = "The Iron Horizon",
            authors = listOf("Remy Vaux", "Dalia Stern"),
            narrators = listOf("Kit Harker"),
            series = null,
            tracks =
                listOf(
                    SeedTrack(fileName = TRACK_SINGLE_M4B, durationSeconds = 16, trackNumber = 1),
                ),
            chapters =
                listOf(
                    SeedChapter(title = "Chapter 1", startSeconds = 0),
                    SeedChapter(title = "Chapter 2", startSeconds = 8),
                ),
            sidecar = SeedSidecar.OPF,
            hasCover = true,
            description = "Two engineers. One impossible bridge. A city that bets everything on them.",
        ),
    )

    // Single-file with DESC_TXT — rounds out the sidecar coverage without repeating a kind
    // used in the representative set; also ensures we stay at exactly 10 books.
    add(
        SeedBook(
            folderPath = "Petra Lund/The Hollow Winter",
            title = "The Hollow Winter",
            authors = listOf("Petra Lund"),
            narrators = listOf("Soren Mast"),
            series = null,
            tracks =
                listOf(
                    SeedTrack(fileName = TRACK_SINGLE_M4B, durationSeconds = 14, trackNumber = 1),
                ),
            chapters =
                listOf(
                    SeedChapter(title = "First Snow", startSeconds = 0),
                    SeedChapter(title = "The Long Dark", startSeconds = 7),
                ),
            sidecar = SeedSidecar.READER_TXT,
            hasCover = true,
            description = "A lighthouse keeper. Eleven months of silence. One letter that changes everything.",
        ),
    )
}

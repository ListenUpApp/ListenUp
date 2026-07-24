package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookFacet
import com.calypsan.listenup.client.design.components.FacetChipRow
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.features.bookdetail.components.AboutSection
import com.calypsan.listenup.client.features.bookdetail.components.BookReadersContent
import com.calypsan.listenup.client.features.bookdetail.components.ChapterListItem
import com.calypsan.listenup.client.features.bookdetail.components.ChaptersHeader
import com.calypsan.listenup.client.features.contributors.CastRole
import com.calypsan.listenup.client.features.bookdetail.components.CompactHero
import com.calypsan.listenup.client.features.bookdetail.components.CountBadge
import com.calypsan.listenup.client.features.bookdetail.components.DetailsSection
import com.calypsan.listenup.client.features.contributors.FullCastSheetFor
import com.calypsan.listenup.client.features.bookdetail.components.OfflineBanner
import com.calypsan.listenup.client.features.bookdetail.components.PrimaryActionsSection
import com.calypsan.listenup.client.features.bookdetail.components.ReaderRowUi
import com.calypsan.listenup.client.features.bookdetail.components.StatsRow
import com.calypsan.listenup.client.features.bookdetail.components.WideHeroBand
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel

// ── Mock data ───────────────────────────────────────────────────────────────────

private val WIDE_PREVIEW_WIDTH = 1000.dp

private const val MOCK_BOOK_ID = "the-way-of-kings"
private const val MOCK_TITLE = "The Way of Kings"

// An independent subtitle (not just the series name) plus multi-series membership — the case the
// subtitle + series-chip treatment exists for.
private const val MOCK_SUBTITLE = "A Novel"
private const val MOCK_GENRE = "Epic Fantasy"
private const val MOCK_PROGRESS = 0.62f
private const val MOCK_TIME_REMAINING = "17h 12m left"
private const val MOCK_DURATION_MS = 45L * 3600 * 1000
private const val MOCK_RATING = 4.7
private const val MOCK_YEAR = 2010
private const val MOCK_ADDED_AT_MS = 1_704_067_200_000L

private const val ROLE_AUTHOR = "Author"
private const val ROLE_NARRATOR = "Narrator"

private val mockAuthors =
    listOf(BookContributor(id = "auth-sanderson", name = "Brandon Sanderson", roles = listOf(ROLE_AUTHOR)))

private val mockSeries =
    listOf(
        BookSeries(seriesId = "ser-stormlight", seriesName = "The Stormlight Archive", sequence = "1"),
        BookSeries(seriesId = "ser-cosmere", seriesName = "The Cosmere", sequence = "1"),
    )

// A full narrator cast (4) so the gallery shows the hero fold ("{lead}, N other narrators"),
// the grouped "Narrators" credit row, and the full-cast overlay.
private val mockNarrators =
    listOf(
        BookContributor(id = "narr-kramer", name = "Michael Kramer", roles = listOf(ROLE_NARRATOR)),
        BookContributor(id = "narr-reading", name = "Kate Reading", roles = listOf(ROLE_NARRATOR)),
        BookContributor(id = "narr-vance", name = "Simon Vance", roles = listOf(ROLE_NARRATOR)),
        BookContributor(id = "narr-maarleveld", name = "Saskia Maarleveld", roles = listOf(ROLE_NARRATOR)),
    )

private val mockCredits =
    listOf(BookContributor(id = "auth-sanderson", name = "Brandon Sanderson", roles = listOf(ROLE_AUTHOR))) +
        mockNarrators +
        listOf(
            BookContributor(id = "trans-vega", name = "Isabel Vega", roles = listOf("Translator")),
            BookContributor(id = "ed-okafor", name = "Daniel Okafor", roles = listOf("Editor", "Foreword")),
        )

private val mockAudioFiles =
    listOf(
        AudioFile(
            id = "1",
            index = 0,
            filename = "01.m4b",
            format = "m4b",
            codec = "ac4",
            duration = 3_600_000L,
            size = 1_000_000L,
            spatial = "atmos",
            bitrate = 320_000,
            sampleRate = 48_000,
            channels = 6,
        ),
    )

private val mockGenres = listOf("Fantasy", "Epic Fantasy", "Adventure", "Fiction")

private val mockGenreObjects =
    listOf(
        Genre(id = "g1", name = "Fantasy", slug = "fantasy", path = "/fantasy"),
        Genre(id = "g2", name = "Epic Fantasy", slug = "epic-fantasy", path = "/fantasy/epic-fantasy"),
        Genre(id = "g3", name = "Adventure", slug = "adventure", path = "/adventure"),
        Genre(id = "g4", name = "Fiction", slug = "fiction", path = "/fiction"),
    )

private val mockTags =
    listOf(
        Tag(id = "t1", name = "Found Family", slug = "found-family"),
        Tag(id = "t2", name = "Slow Burn", slug = "slow-burn"),
        Tag(id = "t3", name = "Magic System", slug = "magic-system"),
        Tag(id = "t4", name = "Morally Grey", slug = "morally-grey"),
    )

private val mockMoods =
    listOf(
        Mood(id = "m1", name = "Epic", slug = "epic"),
        Mood(id = "m2", name = "Tense", slug = "tense"),
        Mood(id = "m3", name = "Hopeful", slug = "hopeful"),
    )

private const val MOCK_DESCRIPTION =
    "Roshar is a world of stone and storms. Uncanny tempests of incredible power sweep across the " +
        "rocky terrain so frequently that they have shaped ecology and civilization alike. Animals " +
        "hide in shells, trees pull in branches, and grass retracts into the soilless ground. " +
        "Cities are built only where the topography offers shelter.\n\n" +
        "It has been centuries since the fall of the ten consecrated orders known as the Knights " +
        "Radiant, but their Shardblades and Shardplate remain: mystical swords and suits of armor " +
        "that transform ordinary men into near-invincible warriors. Men trade kingdoms for " +
        "Shardblades. Wars were fought for them, and won by them."

private val mockChapters =
    listOf(
        ChapterUiModel(id = "c1", title = "Prologue: To Kill", duration = "18:42", imageUrl = null),
        ChapterUiModel(id = "c2", title = "Honor Is Dead", duration = "41:09", imageUrl = null),
        ChapterUiModel(id = "c3", title = "The Shattered Plains", duration = "55:31", imageUrl = null),
        ChapterUiModel(id = "c4", title = "The Glory of Ignorance", duration = "37:18", imageUrl = null),
        ChapterUiModel(id = "c5", title = "Bridge Four", duration = "1:02:55", imageUrl = null),
    )

private val mockReaders =
    listOf(
        ReaderRowUi(userId = "u1", name = "Simon Hull", isReading = true, progressPct = 62, finishedWhen = null),
        ReaderRowUi(userId = "u2", name = "Vin Venture", isReading = true, progressPct = null, finishedWhen = null),
        ReaderRowUi(
            userId = "u3",
            name = "Kaladin Stormblessed",
            isReading = false,
            progressPct = null,
            finishedWhen = "Apr 12",
        ),
        ReaderRowUi(
            userId = "u4",
            name = "Shallan Davar",
            isReading = false,
            progressPct = null,
            finishedWhen = "Mar 03",
        ),
    )

// ── Gallery ─────────────────────────────────────────────────────────────────────

/**
 * On-device gallery of the Book Detail components rendered with mock data — a fallback for
 * validating the redesign without a live server or real book data when the IDE preview pane is
 * unavailable. Launched by the debug `PreviewGalleryActivity` via `--es gallery bookdetail`; not
 * part of the navigation graph.
 *
 * Avatars in the Readers section render as grey placeholders (no Koin-resolved server); the active
 * ring, progress bar, and finished treatments are what this gallery verifies there.
 */
@Composable
fun BookDetailPreviewGallery() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            HeroSection()
            StatsSection()
            GenresVsTagsSection()
            AboutSectionGallery()
            DetailsSectionGallery()
            ActionsSection()
            OfflineSection()
            BadgesSection()
            ChaptersSection()
            ReadersSection()

            HorizontalDivider()
        }
    }
}

@Composable
private fun HeroSection() {
    // Tapping a folded contributor line opens the full-cast overlay, same as the real screen.
    var castRole by remember { mutableStateOf<CastRole?>(null) }

    GalleryLabel("Hero — compact (phone)")
    CompactHero(
        coverPath = null,
        coverHash = null,
        bookId = MOCK_BOOK_ID,
        title = MOCK_TITLE,
        genre = MOCK_GENRE,
        abridged = false,
        subtitle = MOCK_SUBTITLE,
        series = mockSeries,
        authors = mockAuthors,
        narrators = mockNarrators,
        onContributorClick = {},
        onSeriesClick = {},
        onShowCast = { castRole = it },
        progress = MOCK_PROGRESS,
        timeRemaining = MOCK_TIME_REMAINING,
    )

    GalleryLabel("Hero — wide band (tablet / desktop) — scroll horizontally →")
    WidePreview {
        WideHeroBand(
            coverPath = null,
            coverHash = null,
            bookId = MOCK_BOOK_ID,
            title = MOCK_TITLE,
            genre = MOCK_GENRE,
            abridged = false,
            subtitle = MOCK_SUBTITLE,
            series = mockSeries,
            authors = mockAuthors,
            narrators = mockNarrators,
            onContributorClick = {},
            onSeriesClick = {},
            onShowCast = { castRole = it },
            progress = MOCK_PROGRESS,
            timeRemaining = MOCK_TIME_REMAINING,
            rating = MOCK_RATING,
            duration = MOCK_DURATION_MS,
            year = MOCK_YEAR,
            addedAt = MOCK_ADDED_AT_MS,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    castRole?.let { role ->
        FullCastSheetFor(
            role = role,
            authors = mockAuthors,
            narrators = mockNarrators,
            onContributorClick = {},
            onDismiss = { castRole = null },
        )
    }
}

/**
 * Renders a width-constrained (tablet/desktop) component at its intended width inside a horizontal
 * scroller, so wide-only layouts don't collapse when the gallery is viewed on a narrow phone.
 */
@Composable
private fun WidePreview(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Box(modifier = Modifier.width(WIDE_PREVIEW_WIDTH)) { content() }
    }
}

@Composable
private fun StatsSection() {
    GalleryLabel("Stats — with rating")
    StatsRow(
        rating = MOCK_RATING,
        duration = MOCK_DURATION_MS,
        year = MOCK_YEAR,
        addedAt = MOCK_ADDED_AT_MS,
        modifier = horizontalGutter(),
    )

    GalleryLabel("Stats — no rating")
    StatsRow(
        rating = null,
        duration = MOCK_DURATION_MS,
        year = MOCK_YEAR,
        addedAt = MOCK_ADDED_AT_MS,
        modifier = horizontalGutter(),
    )
}

@Composable
private fun GenresVsTagsSection() {
    GalleryLabel("Genres vs Tags (outlined vs filled)")
    Column(
        modifier = horizontalGutter(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FacetChipRow(
            labels = mockGenres,
            facet = BookFacet.Genre,
            onClick = {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        )
        FacetChipRow(
            labels = mockTags.map { it.displayName() },
            facet = BookFacet.Tag,
            onClick = {},
        )
        FacetChipRow(labels = mockMoods.map { it.name }, facet = BookFacet.Mood)
    }
}

@Composable
private fun AboutSectionGallery() {
    GalleryLabel("About — frameless (compact)")
    AboutSection(
        description = MOCK_DESCRIPTION,
        genres = mockGenreObjects,
        tags = mockTags,
        moods = mockMoods,
        isLoadingTags = false,
        isCard = false,
        isDescriptionExpanded = false,
        onToggleDescriptionExpanded = {},
        onGenreClick = {},
        onTagClick = {},
        onMoodClick = {},
        modifier = horizontalGutter(),
    )

    GalleryLabel("About — card with credits (wide)")
    AboutSection(
        description = MOCK_DESCRIPTION,
        genres = mockGenreObjects,
        tags = mockTags,
        moods = mockMoods,
        isLoadingTags = false,
        isCard = true,
        isDescriptionExpanded = false,
        onToggleDescriptionExpanded = {},
        onGenreClick = {},
        onTagClick = {},
        onMoodClick = {},
        modifier = horizontalGutter(),
        creditsSlot = null,
    )
}

@Composable
private fun DetailsSectionGallery() {
    GalleryLabel("Details — publisher / published / language / format + credits")
    DetailsSection(
        publisher = "Tor Books",
        publishYear = MOCK_YEAR,
        language = "en",
        audioFiles = mockAudioFiles,
        credits = mockCredits,
        onContributorClick = {},
        modifier = horizontalGutter(),
    )
}

@Composable
private fun ActionsSection() {
    GalleryLabel("Actions — not downloaded, play enabled")
    PrimaryActionsSection(
        downloadStatus = BookDownloadStatus.NotDownloaded(MOCK_BOOK_ID),
        onPlayClick = {},
        onDownloadClick = {},
        onCancelClick = {},
        onDeleteClick = {},
        modifier = horizontalGutter(),
    )

    GalleryLabel("Actions — downloading")
    PrimaryActionsSection(
        downloadStatus =
            BookDownloadStatus.InProgress(
                bookId = MOCK_BOOK_ID,
                totalFiles = 12,
                downloadingFiles = 1,
                completedFiles = 4,
                totalBytes = 500_000_000L,
                downloadedBytes = 180_000_000L,
            ),
        onPlayClick = {},
        onDownloadClick = {},
        onCancelClick = {},
        onDeleteClick = {},
        modifier = horizontalGutter(),
    )

    GalleryLabel("Actions — offline (play disabled, server warning)")
    PrimaryActionsSection(
        downloadStatus = BookDownloadStatus.NotDownloaded(MOCK_BOOK_ID),
        onPlayClick = {},
        onDownloadClick = {},
        onCancelClick = {},
        onDeleteClick = {},
        modifier = horizontalGutter(),
        playEnabled = false,
        downloadEnabled = false,
        showServerWarning = true,
    )
}

@Composable
private fun OfflineSection() {
    GalleryLabel("Offline banner — full")
    OfflineBanner(onRetryClick = {}, modifier = horizontalGutter(), compact = false)

    GalleryLabel("Offline banner — compact")
    OfflineBanner(onRetryClick = {}, modifier = horizontalGutter(), compact = true)
}

@Composable
private fun BadgesSection() {
    GalleryLabel("Count badges")
    Row(
        modifier = horizontalGutter(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CountBadge(count = 5)
        CountBadge(count = 42)
        CountBadge(count = 128)
    }
}

@Composable
private fun ChaptersSection() {
    GalleryLabel("Chapters")
    Column(modifier = horizontalGutter()) {
        ChaptersHeader(chapterCount = mockChapters.size)
        mockChapters.forEachIndexed { index, chapter ->
            ChapterListItem(
                chapter = chapter,
                chapterNumber = index + 1,
                isCurrent = index == 2,
                showDivider = index < mockChapters.lastIndex,
            )
        }
    }
}

@Composable
private fun ReadersSection() {
    GalleryLabel("Readers — mixed (reading %, reading unknown, finished)")
    BookReadersContent(
        readers = mockReaders,
        listeningNowCount = mockReaders.count { it.isReading },
        totalCount = mockReaders.size + 3,
        isCard = false,
        onUserClick = {},
        onSeeAllClick = {},
        modifier = horizontalGutter(),
    )
}

// ── Helpers ─────────────────────────────────────────────────────────────────────

private fun horizontalGutter(): Modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)

@Composable
private fun GalleryLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

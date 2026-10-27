package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.client.features.home.components.HomeHeader
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.presentation.home.HomeConnectivity
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.TimeOfDay
import androidx.compose.ui.tooling.preview.Preview

// ── Mock data ─────────────────────────────────────────────────────────────────

private const val PREVIEW_USER_NAME = "Simon"

private fun mockBook(
    id: String,
    title: String,
    author: String,
    progress: Float,
) = ContinueListeningBook(
    bookId = id,
    title = title,
    authorNames = author,
    coverPath = null,
    progress = progress,
    currentPositionMs = (progress * 36_000_000).toLong(),
    totalDurationMs = 36_000_000L,
    narratorNames = null,
    lastPlayedAtMs = 1_704_067_200_000L,
    isDownloaded = false,
)

private fun mockContinue() =
    listOf(
        ContinueListeningItem.Ready(
            "1",
            mockBook(id = "1", title = "The Institute", author = "Stephen King", progress = 0.5f),
        ),
        ContinueListeningItem.Ready(
            "2",
            mockBook(id = "2", title = "North! Or Be Eaten", author = "Andrew Peterson", progress = 0.1f),
        ),
        ContinueListeningItem.Ready(
            "3",
            mockBook(id = "3", title = "Project Hail Mary", author = "Andy Weir", progress = 0.22f),
        ),
    )

private fun mockShelf(
    id: String,
    name: String,
    count: Int,
) = Shelf(
    id = ShelfId(id),
    name = name,
    description = null,
    isPrivate = false,
    ownerId = "owner",
    ownerDisplayName = PREVIEW_USER_NAME,
    bookCount = count,
    totalDurationSeconds = 0L,
    createdAtMs = 0L,
    updatedAtMs = 0L,
)

private fun mockShelves() =
    listOf(
        mockShelf("a", "Sci-Fi & Fantasy", 12),
        mockShelf("b", "Finished", 48),
        mockShelf("c", "Want to Listen", 7),
    )

private fun mockStats() =
    HomeStatsUiState.Data(
        totalSecondsThisWeek = 15_300L,
        currentStreakDays = 5,
        longestStreakDays = 14,
        dailyBuckets =
            listOf(
                DayBucket(0, 4_200L),
                DayBucket(1, 0L),
                DayBucket(2, 1_680L),
                DayBucket(3, 3_720L),
                DayBucket(4, 2_880L),
                DayBucket(5, 720L),
                DayBucket(6, 2_100L),
            ),
        topGenres =
            listOf(
                GenreShare("Fiction", 4_600L),
                GenreShare("Sci-Fi & Fantasy", 3_100L),
                GenreShare("Mystery & Thriller", 2_300L),
            ),
    )

private val zeroStats =
    HomeStatsUiState.Data(
        totalSecondsThisWeek = 0L,
        currentStreakDays = 0,
        longestStreakDays = 0,
        dailyBuckets = List(7) { DayBucket(it, 0L) },
        topGenres = emptyList(),
    )

private fun mockReady(slot: HomeSlot) =
    HomeUiState.Ready(
        myShelves = mockShelves(),
        isSyncing = false,
        isBuildingInitialLibrary = false,
        isLibraryArriving = false,
        scanProgress = null,
        timeOfDay = TimeOfDay.Evening,
        firstName = PREVIEW_USER_NAME,
        slot = slot,
        everythingInProgress = mockContinue(),
        connectivity = HomeConnectivity.Online,
        continueListeningError = null,
    )

/** The shell header stands in as a plain row: the greeting and nothing else. */
private val previewHeader: AppHeaderSlot = { leading ->
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin)) { leading() }
}

// ── Preview harness ─────────────────────────────────────────────────────────

@Composable
private fun PreviewSurface(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) { content() }
    }
}

@Composable
private fun BodyPreview(
    slot: HomeSlot,
    width: HomeWidth,
) = ListenUpTheme(dynamicColor = false) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        HomeBody(
            state = mockReady(slot),
            stats = mockStats(),
            width = width,
            actions = HomeActions.None,
            selection = HomeSelection.None,
            appHeader = previewHeader,
            contentPadding = PaddingValues(),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(heightDp = 1400)
@Composable
private fun HomeLoadedCompactPreview() = BodyPreview(HomeSlot.Absent, HomeWidth.Compact)

@Preview
@Composable
private fun GreetingPreview() =
    PreviewSurface {
        HomeHeader(timeOfDay = TimeOfDay.Evening, firstName = PREVIEW_USER_NAME, isWide = false)
    }

@Preview
@Composable
private fun StatsCardCompactPreview() = PreviewSurface { StatsCard(mockStats(), isWide = false) }

@Preview(widthDp = 900)
@Composable
private fun StatsCardWidePreview() = PreviewSurface { StatsCard(mockStats(), isWide = true) }

@Preview
@Composable
private fun StatsCardZeroPreview() = PreviewSurface { StatsCard(zeroStats, isWide = false) }

@Preview
@Composable
private fun StatsCardDarkPreview() = PreviewSurface(dark = true) { StatsCard(mockStats(), isWide = false) }

@Preview
@Composable
private fun ShelvesCompactPreview() = PreviewSurface { Shelves(HomeWidth.Compact) }

@Preview(widthDp = 900)
@Composable
private fun ShelvesWidePreview() = PreviewSurface { Shelves(HomeWidth.Expanded) }

@Composable
private fun StatsCard(
    stats: HomeStatsUiState,
    isWide: Boolean,
) = HomeStatsCard(
    stats = stats,
    isWide = isWide,
    onOpenLeaderboard = {},
    onRetry = {},
    modifier = Modifier.padding(horizontal = Spacing.screenMargin),
)

@Composable
private fun Shelves(width: HomeWidth) =
    MyShelvesSection(shelves = mockShelves(), width = width, onOpenShelf = {}, onCreateShelf = {})

/**
 * On-device gallery of the Home components rendered with mock data — a fallback for validating the
 * design without real listening data when the IDE preview pane is unavailable. Launched by the
 * debug `PreviewGalleryActivity`; not part of the navigation graph.
 */
@Composable
fun HomePreviewGallery() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            GalleryLabel("Greeting")
            HomeHeader(timeOfDay = TimeOfDay.Evening, firstName = PREVIEW_USER_NAME, isWide = false)

            GalleryLabel("Everything in progress")
            EverythingInProgressSection(
                items = mockContinue(),
                width = HomeWidth.Compact,
                selection = HomeSelection.None,
                onOpenBook = {},
                onOpenInProgress = {},
            )

            GalleryLabel("Your last 7 days — populated (compact)")
            StatsCard(mockStats(), isWide = false)

            GalleryLabel("Your last 7 days — populated (wide)")
            StatsCard(mockStats(), isWide = true)

            GalleryLabel("Your last 7 days — zero state")
            StatsCard(zeroStats, isWide = false)

            GalleryLabel("My shelves (compact)")
            Shelves(HomeWidth.Compact)

            GalleryLabel("My shelves (wide)")
            Shelves(HomeWidth.Expanded)

            HorizontalDivider()
        }
    }
}

@Composable
private fun GalleryLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Spacing.screenMargin),
    )
}

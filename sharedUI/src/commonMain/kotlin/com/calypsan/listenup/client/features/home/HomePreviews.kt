package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.client.features.home.components.ContinueListeningRow
import com.calypsan.listenup.client.features.home.components.HomeHeader
import com.calypsan.listenup.client.features.home.components.HomeStatsContent
import com.calypsan.listenup.client.features.home.components.MyShelvesRow
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
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
    lastPlayedAt = "2026-06-04T12:00:00Z",
)

private fun mockContinue() =
    listOf(
        ContinueListeningItem.Ready("1", mockBook("1", "The Institute", "Stephen King", 0.5f)),
        ContinueListeningItem.Ready("2", mockBook("2", "North! Or Be Eaten", "Andrew Peterson", 0.1f)),
        ContinueListeningItem.Ready("3", mockBook("3", "Project Hail Mary", "Andy Weir", 0.22f)),
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
    coverPaths = emptyList(),
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

/** The stats body wrapped in the same card the real section uses, for an accurate preview. */
@Composable
private fun StatsCardPreview(
    state: HomeStatsUiState.Data,
    isWide: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(Modifier.padding(if (isWide) 28.dp else 22.dp)) { HomeStatsContent(state) }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview
@Composable
private fun GreetingPreview() =
    PreviewSurface {
        HomeHeader(timeGreeting = "Good evening", userName = PREVIEW_USER_NAME, isWide = false)
    }

@Preview
@Composable
private fun ContinueListeningPreview() =
    PreviewSurface {
        ContinueListeningRow(items = mockContinue(), onBookClick = {})
    }

@Preview
@Composable
private fun StatsCardCompactPreview() = PreviewSurface { StatsCardPreview(mockStats(), isWide = false) }

@Preview(widthDp = 900)
@Composable
private fun StatsCardWidePreview() = PreviewSurface { StatsCardPreview(mockStats(), isWide = true) }

@Preview
@Composable
private fun StatsCardZeroPreview() = PreviewSurface { StatsCardPreview(zeroStats, isWide = false) }

@Preview
@Composable
private fun ShelvesCompactPreview() =
    PreviewSurface { MyShelvesRow(shelves = mockShelves(), isWide = false, onShelfClick = {}, onSeeAllClick = {}) }

@Preview(widthDp = 360)
@Composable
private fun ShelvesWidePreview() =
    PreviewSurface { MyShelvesRow(shelves = mockShelves(), isWide = true, onShelfClick = {}, onSeeAllClick = {}) }

@Preview
@Composable
private fun StatsCardDarkPreview() = PreviewSurface(dark = true) { StatsCardPreview(mockStats(), isWide = false) }

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
            HomeHeader(timeGreeting = "Good evening", userName = PREVIEW_USER_NAME, isWide = false)

            GalleryLabel("Continue Listening")
            ContinueListeningRow(items = mockContinue(), onBookClick = {})

            GalleryLabel("This week — populated (compact)")
            StatsCardPreview(mockStats(), isWide = false)

            GalleryLabel("This week — populated (wide)")
            StatsCardPreview(mockStats(), isWide = true)

            GalleryLabel("This week — zero state")
            StatsCardPreview(zeroStats, isWide = false)

            GalleryLabel("My Shelves (compact)")
            MyShelvesRow(shelves = mockShelves(), isWide = false, onShelfClick = {}, onSeeAllClick = {})

            GalleryLabel("My Shelves (wide)")
            MyShelvesRow(shelves = mockShelves(), isWide = true, onShelfClick = {}, onSeeAllClick = {})

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
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

package com.calypsan.listenup.client.features.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.design.components.ListenUpAsyncImage
import com.calypsan.listenup.client.design.components.rememberUserAvatarImage
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.domain.model.ProfileShelfSummary
import com.calypsan.listenup.client.domain.model.ProfileRecentBook
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.client.presentation.profile.UserProfileViewModel
import kotlin.time.Duration.Companion.milliseconds
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_displayname_avatar
import listenup.composeapp.generated.resources.profile_create_shelf
import listenup.composeapp.generated.resources.profile_edit_profile
import listenup.composeapp.generated.resources.profile_recently_finished
import listenup.composeapp.generated.resources.profile_shelf_books_count
import listenup.composeapp.generated.resources.profile_shelves

/**
 * Screen displaying a user's full profile — a color-blocked hero with the scallop avatar,
 * colored stat tiles, recent finished books, and a shelves grid.
 *
 * @param userId The ID of the user to display
 * @param onBack Callback when back button is clicked
 * @param onEditClick Callback when edit button is clicked (own profile only)
 * @param onBookClick Callback when a book is clicked
 * @param onShelfClick Callback when a shelf is clicked
 * @param onCreateShelfClick Callback when "New shelf" is tapped (own profile only)
 * @param viewModel The ViewModel for profile data
 */
@Composable
fun UserProfileScreen(
    userId: String,
    onBack: () -> Unit,
    onEditClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onCreateShelfClick: () -> Unit,
    refreshKey: Int = 0,
    modifier: Modifier = Modifier,
    viewModel: UserProfileViewModel = koinViewModel(),
) {
    LaunchedEffect(userId, refreshKey) {
        viewModel.loadProfile(userId, forceRefresh = refreshKey > 0)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    ListenUpScaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            when (val current = state) {
                is UserProfileUiState.Idle,
                is UserProfileUiState.Loading,
                -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is UserProfileUiState.Error -> {
                    Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                is UserProfileUiState.Ready -> {
                    ProfileContent(
                        state = current,
                        onBack = onBack,
                        onEditClick = onEditClick,
                        onBookClick = onBookClick,
                        onShelfClick = onShelfClick,
                        onCreateShelfClick = onCreateShelfClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileContent(
    state: UserProfileUiState.Ready,
    onBack: () -> Unit,
    onEditClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onCreateShelfClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        // Color-blocked hero
        item {
            ProfileColorHero(
                state = state,
                onBack = onBack,
                onEditClick = onEditClick,
            )
        }

        // Stats
        item {
            Spacer(modifier = Modifier.height(20.dp))
            StatsRow(
                totalListenTime = DurationFormatter.hoursMinutes(state.totalListenTimeMs.milliseconds),
                booksFinished = state.booksFinished,
                currentStreak = state.currentStreak,
                longestStreak = state.longestStreak,
            )
        }

        // Recent finished
        if (state.recentBooks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(28.dp))
                SectionHeader(title = stringResource(Res.string.profile_recently_finished))
                Spacer(modifier = Modifier.height(14.dp))
                RecentBooksRow(books = state.recentBooks, onBookClick = onBookClick)
            }
        }

        // Shelves
        if (state.publicShelves.isNotEmpty() || state.isOwnProfile) {
            item {
                Spacer(modifier = Modifier.height(28.dp))
                ShelvesSectionHeader(count = state.publicShelves.size)
                Spacer(modifier = Modifier.height(14.dp))
                ShelvesGrid(
                    shelves = state.publicShelves,
                    showAddTile = state.isOwnProfile,
                    onShelfClick = onShelfClick,
                    onCreateShelfClick = onCreateShelfClick,
                )
            }
        }
    }
}

// region hero

@Composable
private fun ProfileColorHero(
    state: UserProfileUiState.Ready,
    onBack: () -> Unit,
    onEditClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        HeroBlob(
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 70.dp, y = (-50).dp).size(210.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
            shape = BlobShape,
        )
        HeroBlob(
            modifier = Modifier.align(Alignment.BottomStart).offset(x = (-60).dp, y = 80.dp).size(190.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
            shape = CircleShape,
        )

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            HeroNavRow(onBack = onBack) {
                if (state.isOwnProfile) {
                    IconButton(
                        onClick = onEditClick,
                        modifier =
                            Modifier.size(48.dp).background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                CircleShape,
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(Res.string.profile_edit_profile),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProfileScallopAvatar(state = state)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    fontWeight = FontWeight.ExtraBold,
                    color = ink,
                    textAlign = TextAlign.Center,
                )
                if (!state.tagline.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.tagline!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ink.copy(alpha = 0.82f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 120dp scallop avatar with a brand rim — image (clipped to the scallop) or tonal initials. */
@Composable
private fun ProfileScallopAvatar(state: UserProfileUiState.Ready) {
    val context = LocalPlatformContext.current
    val scallop = cookieScallopShape()
    // Resolve the image the same reactive way as every other avatar: a synced avatar change or a
    // completed download flips this to the photo in real time. Whenever there IS an image, show it.
    val avatarImage = rememberUserAvatarImage(state.userId)

    Box(
        modifier = Modifier.size(132.dp).clip(scallop).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarImage != null) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(avatarImage.localPath)
                        .memoryCacheKey(avatarImage.cacheKey)
                        .diskCacheKey(avatarImage.cacheKey)
                        .build(),
                contentDescription = stringResource(Res.string.common_displayname_avatar, state.displayName),
                modifier = Modifier.size(120.dp).clip(scallop),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.size(120.dp).clip(scallop).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initialsOf(state.displayName),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

@Composable
private fun HeroBlob(
    modifier: Modifier,
    color: Color,
    shape: Shape,
) {
    Box(modifier = modifier.clip(shape).background(color))
}

private val BlobShape =
    RoundedCornerShape(
        topStartPercent = 46,
        topEndPercent = 54,
        bottomEndPercent = 46,
        bottomStartPercent = 54,
    )

// endregion

// region stats

private data class StatTileData(
    val icon: ImageVector,
    val value: String,
    val label: String,
    val container: Color,
    val onContainer: Color,
    val iconTint: Color,
)

@Composable
private fun StatsRow(
    totalListenTime: String,
    booksFinished: Int,
    currentStreak: Int,
    longestStreak: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tiles =
        listOf(
            StatTileData(
                icon = Icons.Default.Schedule,
                value = totalListenTime,
                label = "Listened",
                container = scheme.primaryContainer,
                onContainer = scheme.onPrimaryContainer,
                iconTint = scheme.primary,
            ),
            StatTileData(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                value = booksFinished.toString(),
                label = "Finished",
                container = scheme.tertiaryContainer,
                onContainer = scheme.onTertiaryContainer,
                iconTint = scheme.tertiary,
            ),
            StatTileData(
                icon = Icons.Default.LocalFireDepartment,
                value = "${currentStreak}d",
                label = "Streak",
                container = scheme.secondaryContainer,
                onContainer = scheme.onSecondaryContainer,
                iconTint = scheme.secondary,
            ),
            StatTileData(
                icon = Icons.Default.EmojiEvents,
                value = "${longestStreak}d",
                label = "Best",
                container = scheme.surfaceContainerHigh,
                onContainer = scheme.onSurface,
                iconTint = scheme.primary,
            ),
        )
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        tiles.forEach { StatTile(it, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun StatTile(
    data: StatTileData,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(20.dp))
                .background(data.container)
                .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(cookieScallopShape()).background(data.onContainer.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(data.icon, null, tint = data.iconTint, modifier = Modifier.size(21.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = data.value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = data.onContainer,
                maxLines = 1,
            )
            Text(
                text = data.label,
                style = MaterialTheme.typography.bodySmall,
                color = data.onContainer.copy(alpha = 0.82f),
                maxLines = 1,
            )
        }
    }
}

// endregion

// region shelves

@Composable
private fun ShelvesSectionHeader(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.profile_shelves),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        if (count > 0) {
            Box(
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ShelvesGrid(
    shelves: List<ProfileShelfSummary>,
    showAddTile: Boolean,
    onShelfClick: (String) -> Unit,
    onCreateShelfClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two tiles per row; the add tile trails the list.
    val tiles: List<ProfileShelfSummary?> = shelves + if (showAddTile) listOf(null) else emptyList()
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                rowTiles.forEachIndexed { index, shelf ->
                    if (shelf != null) {
                        ShelfTile(
                            shelf = shelf,
                            colorIndex = shelves.indexOf(shelf),
                            onClick = { onShelfClick(shelf.id) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        AddShelfTile(onClick = onCreateShelfClick, modifier = Modifier.weight(1f))
                    }
                }
                // Pad a trailing single tile so it doesn't stretch full width.
                if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShelfTile(
    shelf: ProfileShelfSummary,
    colorIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, onContainer) =
        when (colorIndex.mod(3)) {
            0 -> scheme.primaryContainer to scheme.onPrimaryContainer
            1 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
            else -> scheme.secondaryContainer to scheme.onSecondaryContainer
        }
    Box(
        modifier =
            modifier
                .height(112.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(container)
                .clickable(onClick = onClick),
    ) {
        HeroBlob(
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 22.dp, y = 22.dp).size(84.dp),
            color = onContainer.copy(alpha = 0.12f),
            shape = BlobShape,
        )
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Default.Bookmarks, null, tint = onContainer, modifier = Modifier.size(24.dp))
            Column {
                Text(
                    text = shelf.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.profile_shelf_books_count, shelf.bookCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun AddShelfTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .height(112.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.profile_create_shelf),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// endregion

// region recent books (kept from prior screen)

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.ExtraBold,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun RecentBooksRow(
    books: List<ProfileRecentBook>,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BrowseCarousel(
        items = books,
        modifier = modifier,
        itemWidth = 140.dp,
        itemSpacing = 16.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
        key = { it.bookId },
    ) { book ->
        RecentBookCard(book = book, onClick = { onBookClick(book.bookId) })
    }
}

@Composable
private fun RecentBookCard(
    book: ProfileRecentBook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(140.dp).clickable(onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            ListenUpAsyncImage(
                path = book.coverPath,
                contentDescription = book.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// endregion

private fun initialsOf(displayName: String): String =
    displayName
        .trim()
        .split("\\s+".toRegex())
        .let { parts ->
            when {
                parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
                displayName.length >= 2 -> displayName.take(2)
                else -> displayName.take(1)
            }
        }.uppercase()

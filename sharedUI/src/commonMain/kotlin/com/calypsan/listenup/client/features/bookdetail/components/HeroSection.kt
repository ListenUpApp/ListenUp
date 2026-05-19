package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.CoverColors
import com.calypsan.listenup.client.design.components.ElevatedCoverCard
import com.calypsan.listenup.client.design.components.ProgressOverlay
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.LocalDarkTheme
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.book_detail_more_options

/**
 * Hero section with color-extracted gradient background.
 * Uses Palette API colors for a cohesive look that matches the cover art.
 */
@Suppress("MagicNumber", "LongParameterList")
@Composable
fun HeroSection(
    coverPath: String?,
    bookId: String,
    title: String,
    subtitle: String?,
    progress: Float?,
    timeRemaining: String?,
    coverColors: CoverColors,
    isComplete: Boolean,
    hasProgress: Boolean,
    isAdmin: Boolean,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onFindMetadataClick: () -> Unit,
    onMarkCompleteClick: () -> Unit,
    onDiscardProgressClick: () -> Unit,
    onAddToShelfClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceContainerColor = MaterialTheme.colorScheme.surfaceContainer
    val isDark = LocalDarkTheme.current

    // Take a bite, take a bite... (Sleep Token - The Offering)
    // Expressive gradient: cover color -> surfaceContainer -> surface
    // In dark mode, use subtler alpha to avoid an oppressive feel
    val gradientColors =
        if (isDark) {
            listOf(
                coverColors.darkMuted.copy(alpha = 0.5f),
                coverColors.darkMuted.copy(alpha = 0.3f),
                surfaceContainerColor.copy(alpha = 0.7f),
                surfaceContainerColor,
                surfaceColor,
            )
        } else {
            listOf(
                coverColors.darkMuted,
                coverColors.darkMuted.copy(alpha = 0.85f),
                surfaceContainerColor.copy(alpha = 0.7f),
                surfaceContainerColor,
                surfaceColor,
            )
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(gradientColors),
                ),
    ) {
        // Content overlay
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Navigation bar with actions menu
            HeroNavigationBar(
                onBackClick = onBackClick,
                isComplete = isComplete,
                hasProgress = hasProgress,
                isAdmin = isAdmin,
                onEditClick = onEditClick,
                onFindMetadataClick = onFindMetadataClick,
                onMarkCompleteClick = onMarkCompleteClick,
                onDiscardProgressClick = onDiscardProgressClick,
                onAddToShelfClick = onAddToShelfClick,
                onAddToCollectionClick = onAddToCollectionClick,
                onShareClick = onShareClick,
                onDeleteClick = onDeleteClick,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Floating cover card
            FloatingCoverCard(
                coverPath = coverPath,
                bookId = bookId,
                title = title,
                progress = progress,
                timeRemaining = timeRemaining,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title - Magazine headline style with high contrast for dark mode
            Text(
                text = title,
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp),
            )

            // Subtitle
            subtitle?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * Navigation bar with translucent back button and three-dot actions menu.
 * Uses surfaceContainerHigh for better contrast against dynamic cover colors.
 */
@Suppress("LongParameterList")
@Composable
private fun HeroNavigationBar(
    onBackClick: () -> Unit,
    isComplete: Boolean,
    hasProgress: Boolean,
    isAdmin: Boolean,
    onEditClick: () -> Unit,
    onFindMetadataClick: () -> Unit,
    onMarkCompleteClick: () -> Unit,
    onDiscardProgressClick: () -> Unit,
    onAddToShelfClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val buttonBackground = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Back button
        IconButton(
            onClick = onBackClick,
            modifier =
                Modifier
                    .size(48.dp)
                    .background(
                        color = buttonBackground,
                        shape = CircleShape,
                    ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.common_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Three-dot menu
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier =
                    Modifier
                        .size(48.dp)
                        .background(
                            color = buttonBackground,
                            shape = CircleShape,
                        ),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.book_detail_more_options),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            BookActionsMenu(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                isComplete = isComplete,
                hasProgress = hasProgress,
                isAdmin = isAdmin,
                onEditClick = {
                    showMenu = false
                    onEditClick()
                },
                onFindMetadataClick = {
                    showMenu = false
                    onFindMetadataClick()
                },
                onMarkCompleteClick = {
                    showMenu = false
                    onMarkCompleteClick()
                },
                onDiscardProgressClick = {
                    showMenu = false
                    onDiscardProgressClick()
                },
                onAddToShelfClick = {
                    showMenu = false
                    onAddToShelfClick()
                },
                onAddToCollectionClick = {
                    showMenu = false
                    onAddToCollectionClick()
                },
                onShareClick = {
                    showMenu = false
                    onShareClick()
                },
                onDeleteClick = {
                    showMenu = false
                    onDeleteClick()
                },
            )
        }
    }
}

/**
 * Floating cover card (240dp width) - the visual anchor.
 */
@Composable
private fun FloatingCoverCard(
    coverPath: String?,
    bookId: String,
    title: String,
    progress: Float?,
    timeRemaining: String?,
) {
    Box(contentAlignment = Alignment.Center) {
        ElevatedCoverCard(
            path = coverPath,
            bookId = bookId,
            contentDescription = title,
            modifier =
                Modifier
                    .width(240.dp)
                    .aspectRatio(1f),
        ) {
            progress?.let { prog ->
                ProgressOverlay(
                    progress = prog,
                    timeRemaining = timeRemaining,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@file:Suppress("MagicNumber")

package com.calypsan.listenup.desktop.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.features.nowplaying.WavySeekBar
import com.calypsan.listenup.client.playback.NowPlayingState
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.kmpalette.palette.graphics.Palette
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

/**
 * Full-screen now-playing view for desktop.
 *
 * Dispatches over the [NowPlayingState] sealed hierarchy: [NowPlayingState.Active]
 * gets the full transport / wavy seek bar / overflow menu UI; [NowPlayingState.Preparing]
 * shows a centred preparing card; [NowPlayingState.Error] shows an error card with retry
 * affordances; [NowPlayingState.Idle] shows nothing meaningful (caller normally avoids
 * routing here when Idle).
 *
 * Active layout adapts: wide → cover left / controls right; tall → vertical centre.
 */
@Composable
fun DesktopNowPlayingScreen(
    state: NowPlayingState,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekWithinChapter: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onClose: () -> Unit,
    onBackClick: () -> Unit,
    onGoToBook: (() -> Unit)? = null,
    onGoToSeries: ((String) -> Unit)? = null,
    onGoToContributor: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        when (state) {
            is NowPlayingState.Idle -> {
                IdleScreen(onBackClick = onBackClick)
            }

            is NowPlayingState.Preparing -> {
                PreparingScreen(
                    state = state,
                    onBackClick = onBackClick,
                )
            }

            is NowPlayingState.Error -> {
                ErrorScreen(
                    state = state,
                    onBackClick = onBackClick,
                    onClose = onClose,
                )
            }

            is NowPlayingState.Active -> {
                ActiveScreen(
                    state = state,
                    onPlayPause = onPlayPause,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onSeekWithinChapter = onSeekWithinChapter,
                    onSetSpeed = onSetSpeed,
                    onClose = onClose,
                    onBackClick = onBackClick,
                    onGoToBook = onGoToBook,
                    onGoToSeries = onGoToSeries,
                    onGoToContributor = onGoToContributor,
                )
            }
        }
    }
}

@Composable
private fun IdleScreen(onBackClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No book is currently playing",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreparingScreen(
    state: NowPlayingState.Preparing,
    onBackClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                CoverArt(
                    bookId = state.bookId,
                    coverUrl = state.coverPath,
                    blurHash = state.coverBlurHash,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.author,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.width(240.dp),
            )
            val prepareMessage = state.message
            if (prepareMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = prepareMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorScreen(
    state: NowPlayingState.Error,
    onBackClick: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close player")
            }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (state.title != null) {
                Text(
                    text = "Failed to play ${state.title}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 400.dp),
            )
        }
    }
}

@Composable
private fun ActiveScreen(
    state: NowPlayingState.Active,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekWithinChapter: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onClose: () -> Unit,
    onBackClick: () -> Unit,
    onGoToBook: (() -> Unit)?,
    onGoToSeries: ((String) -> Unit)?,
    onGoToContributor: ((String) -> Unit)?,
) {
    // Dynamic color from cover art
    var dominantColor by remember { mutableStateOf(Color.Transparent) }

    LaunchedEffect(state.coverPath) {
        val coverPath = state.coverPath
        if (coverPath != null) {
            val color = extractDominantColor(coverPath)
            if (color != null) {
                dominantColor = color
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideLayout = maxWidth > maxHeight

        // Glow background
        if (dominantColor != Color.Transparent) {
            if (isWideLayout) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .width(maxWidth * 0.5f)
                            .align(Alignment.CenterStart)
                            .background(
                                brush =
                                    Brush.horizontalGradient(
                                        colorStops =
                                            arrayOf(
                                                0.0f to dominantColor.copy(alpha = 0.5f),
                                                0.3f to dominantColor.copy(alpha = 0.4f),
                                                0.6f to dominantColor.copy(alpha = 0.2f),
                                                0.85f to dominantColor.copy(alpha = 0.05f),
                                                1.0f to Color.Transparent,
                                            ),
                                    ),
                            ),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(550.dp)
                            .offset(y = 80.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                brush =
                                    Brush.verticalGradient(
                                        colorStops =
                                            arrayOf(
                                                0.0f to Color.Transparent,
                                                0.15f to dominantColor.copy(alpha = 0.3f),
                                                0.35f to dominantColor.copy(alpha = 0.6f),
                                                0.5f to dominantColor.copy(alpha = 0.6f),
                                                0.65f to dominantColor.copy(alpha = 0.3f),
                                                0.85f to dominantColor.copy(alpha = 0.1f),
                                                1.0f to Color.Transparent,
                                            ),
                                    ),
                            ),
                )
            }
        }

        if (isWideLayout) {
            WideLayout(
                state = state,
                onPlayPause = onPlayPause,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onSeek = onSeekWithinChapter,
                onSetSpeed = onSetSpeed,
                onClose = onClose,
                onBackClick = onBackClick,
                onGoToBook = onGoToBook,
                onGoToSeries = onGoToSeries,
                onGoToContributor = onGoToContributor,
            )
        } else {
            TallLayout(
                state = state,
                onPlayPause = onPlayPause,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onSeek = onSeekWithinChapter,
                onSetSpeed = onSetSpeed,
                onClose = onClose,
                onBackClick = onBackClick,
                onGoToBook = onGoToBook,
                onGoToSeries = onGoToSeries,
                onGoToContributor = onGoToContributor,
            )
        }
    }
}

// --- Color extraction via kmpalette ---

/**
 * Extract the dominant color from a cover art image file using kmpalette.
 *
 * Loads the image via Skia (CMP's native image backend on desktop),
 * converts to Compose ImageBitmap, then feeds to kmpalette's Palette.Builder.
 * Uses the same swatch priority as Android: vibrant → muted → dominant.
 *
 * Runs on IO dispatcher to avoid blocking the main thread.
 */
private suspend fun extractDominantColor(coverPath: String): Color? =
    withContext(Dispatchers.IO) {
        try {
            val bytes = java.io.File(coverPath).readBytes()
            val skiaImage = Image.makeFromEncoded(bytes)
            val bitmap = skiaImage.toComposeImageBitmap()

            val palette = Palette.from(bitmap).generate()

            val swatch =
                palette.vibrantSwatch
                    ?: palette.mutedSwatch
                    ?: palette.dominantSwatch

            swatch?.rgb?.let { Color(it) }
        } catch (e: Exception) {
            logger.debug(e) { "Color extraction from cover art failed" }
            null
        }
    }

// --- Tall (portrait) layout ---

@Composable
private fun TallLayout(
    state: NowPlayingState.Active,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeek: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onClose: () -> Unit,
    onBackClick: () -> Unit,
    onGoToBook: (() -> Unit)?,
    onGoToSeries: ((String) -> Unit)?,
    onGoToContributor: ((String) -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
    ) {
        TopBar(
            state = state,
            onBackClick = onBackClick,
            onClose = onClose,
            onGoToBook = onGoToBook,
            onGoToSeries = onGoToSeries,
            onGoToContributor = onGoToContributor,
        )

        Spacer(Modifier.height(16.dp))

        // Cover art
        Box(
            modifier =
                Modifier
                    .weight(0.45f)
                    .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            CoverArt(
                bookId = state.bookId,
                coverUrl = state.coverPath,
                blurHash = state.coverBlurHash,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Title section
        TitleSection(
            title = state.title,
            author = state.author,
            chapterTitle = state.chapterTitle,
            chapterLabel = state.chapterLabel,
            centered = true,
        )

        Spacer(Modifier.height(24.dp))

        // Wavy seek bar
        SeekSection(
            progress = state.chapterProgress,
            positionMs = state.chapterPositionMs,
            durationMs = state.chapterDurationMs,
            isPlaying = state.isPlaying,
            onSeek = onSeek,
        )

        Spacer(Modifier.height(32.dp))

        // Transport controls
        TransportControls(
            isPlaying = state.isPlaying,
            onPlayPause = onPlayPause,
            onSkipBack = onSkipBack,
            onSkipForward = onSkipForward,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
        )

        Spacer(Modifier.height(24.dp))

        // Secondary controls
        SecondaryControls(
            playbackSpeed = state.playbackSpeed,
            onSpeedClick = onSetSpeed,
        )

        Spacer(Modifier.height(16.dp))
    }
}

// --- Wide (landscape) layout ---

@Composable
private fun WideLayout(
    state: NowPlayingState.Active,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeek: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onClose: () -> Unit,
    onBackClick: () -> Unit,
    onGoToBook: (() -> Unit)?,
    onGoToSeries: ((String) -> Unit)?,
    onGoToContributor: ((String) -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // Left: cover art
        Box(
            modifier =
                Modifier
                    .weight(0.4f)
                    .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            CoverArt(
                bookId = state.bookId,
                coverUrl = state.coverPath,
                blurHash = state.coverBlurHash,
            )
        }

        // Right: controls
        Column(
            modifier =
                Modifier
                    .weight(0.6f)
                    .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            TopBar(
                state = state,
                onBackClick = onBackClick,
                onClose = onClose,
                onGoToBook = onGoToBook,
                onGoToSeries = onGoToSeries,
                onGoToContributor = onGoToContributor,
            )

            TitleSection(
                title = state.title,
                author = state.author,
                chapterTitle = state.chapterTitle,
                chapterLabel = state.chapterLabel,
                centered = false,
            )

            SeekSection(
                progress = state.chapterProgress,
                positionMs = state.chapterPositionMs,
                durationMs = state.chapterDurationMs,
                isPlaying = state.isPlaying,
                onSeek = onSeek,
            )

            TransportControls(
                isPlaying = state.isPlaying,
                onPlayPause = onPlayPause,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
            )

            SecondaryControls(
                playbackSpeed = state.playbackSpeed,
                onSpeedClick = onSetSpeed,
            )
        }
    }
}

// --- Shared components ---

@Composable
private fun TopBar(
    state: NowPlayingState.Active,
    onBackClick: () -> Unit,
    onClose: () -> Unit,
    onGoToBook: (() -> Unit)?,
    onGoToSeries: ((String) -> Unit)?,
    onGoToContributor: ((String) -> Unit)?,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        Row {
            // Overflow menu (only if navigation callbacks provided)
            if (onGoToBook != null || onGoToSeries != null || onGoToContributor != null) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    OverflowMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        state = state,
                        onGoToBook = onGoToBook,
                        onGoToSeries = onGoToSeries,
                        onGoToContributor = onGoToContributor,
                        onClose = onClose,
                    )
                }
            }

            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close player")
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: NowPlayingState.Active,
    onGoToBook: (() -> Unit)?,
    onGoToSeries: ((String) -> Unit)?,
    onGoToContributor: ((String) -> Unit)?,
    onClose: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (onGoToBook != null) {
            DropdownMenuItem(
                text = { Text("Go to Book") },
                onClick = {
                    onDismiss()
                    onGoToBook()
                },
                leadingIcon = { Icon(Icons.Default.Book, contentDescription = null) },
            )
        }

        val seriesId = state.seriesId
        if (onGoToSeries != null && seriesId != null) {
            DropdownMenuItem(
                text = { Text("Go to Series") },
                onClick = {
                    onDismiss()
                    onGoToSeries(seriesId)
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
            )
        }

        if (onGoToContributor != null && state.authors.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(if (state.hasMultipleAuthors) "Go to Author…" else "Go to Author") },
                onClick = {
                    onDismiss()
                    state.authors.firstOrNull()?.let { onGoToContributor(it.id) }
                },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            )
        }

        if (onGoToContributor != null && state.narrators.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(if (state.hasMultipleNarrators) "Go to Narrator…" else "Go to Narrator") },
                onClick = {
                    onDismiss()
                    state.narrators.firstOrNull()?.let { onGoToContributor(it.id) }
                },
                leadingIcon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
            )
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text("Close Book") },
            onClick = {
                onDismiss()
                onClose()
            },
            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
        )
    }
}

@Composable
private fun CoverArt(
    bookId: String,
    coverUrl: String?,
    blurHash: String?,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 24.dp,
        tonalElevation = 0.dp,
    ) {
        BookCoverImage(
            bookId = bookId,
            coverPath = coverUrl,
            contentDescription = "Book cover",
            blurHash = blurHash,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TitleSection(
    title: String,
    author: String,
    chapterTitle: String?,
    chapterLabel: String,
    centered: Boolean,
) {
    val alignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Text(
            text = title,
            style = if (centered) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.widthIn(max = 400.dp),
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = author,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
        )

        if (chapterTitle != null) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
                modifier = Modifier.widthIn(max = 400.dp),
            )

            Text(
                text = chapterLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = textAlign,
            )
        }
    }
}

/**
 * Wavy seek bar with time labels.
 * Uses the shared WavySeekBar composable from commonMain.
 */
@Composable
private fun SeekSection(
    progress: Float,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().widthIn(max = 480.dp)) {
        WavySeekBar(
            progress = progress.coerceIn(0f, 1f),
            onSeek = onSeek,
            isPlaying = isPlaying,
        )

        // Time labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPlaybackTime(positionMs.milliseconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "-${formatPlaybackTime((durationMs - positionMs).coerceAtLeast(0).milliseconds)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * M3 Expressive connected button group for transport controls.
 *
 * Uniform height, diverse shape (leading/trailing pill, middle squircle),
 * diverse color (play button filled, others tonal), connected layout.
 */
@Composable
private fun TransportControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val buttonHeight = 56.dp
    val cornerRadius = 16.dp
    val fullRadius = 28.dp

    val leadingShape =
        RoundedCornerShape(
            topStart = fullRadius,
            bottomStart = fullRadius,
            topEnd = cornerRadius,
            bottomEnd = cornerRadius,
        )
    val middleShape = RoundedCornerShape(cornerRadius)
    val trailingShape =
        RoundedCornerShape(
            topStart = cornerRadius,
            bottomStart = cornerRadius,
            topEnd = fullRadius,
            bottomEnd = fullRadius,
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Previous chapter — leading pill
        FilledTonalButton(
            onClick = onPreviousChapter,
            modifier = Modifier.weight(1f).height(buttonHeight),
            shape = leadingShape,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter", modifier = Modifier.size(24.dp))
        }

        // Skip back — middle
        FilledTonalButton(
            onClick = onSkipBack,
            modifier = Modifier.weight(1f).height(buttonHeight),
            shape = middleShape,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(Icons.Default.Replay10, contentDescription = "Skip back 10s", modifier = Modifier.size(24.dp))
        }

        // Play/Pause — hero button
        Button(
            onClick = onPlayPause,
            modifier = Modifier.weight(1.4f).height(buttonHeight),
            shape = middleShape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(32.dp),
            )
        }

        // Skip forward — middle
        FilledTonalButton(
            onClick = onSkipForward,
            modifier = Modifier.weight(1f).height(buttonHeight),
            shape = middleShape,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(Icons.Default.Forward30, contentDescription = "Skip forward 30s", modifier = Modifier.size(24.dp))
        }

        // Next chapter — trailing pill
        FilledTonalButton(
            onClick = onNextChapter,
            modifier = Modifier.weight(1f).height(buttonHeight),
            shape = trailingShape,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next chapter", modifier = Modifier.size(24.dp))
        }
    }
}

// --- Speed control ---

private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
private const val MIN_SPEED = 0.5f
private const val MAX_SPEED = 3.0f
private const val SPEED_STEP = 0.05f

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) {
        "${speed.toInt()}.0x"
    } else {
        "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"
    }

private fun snapSpeed(speed: Float): Float = (speed / SPEED_STEP).roundToInt() * SPEED_STEP

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecondaryControls(
    playbackSpeed: Float,
    onSpeedClick: (Float) -> Unit,
) {
    var showPopup by remember { mutableStateOf(false) }
    var sliderSpeed by remember(playbackSpeed) { mutableStateOf(playbackSpeed) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box {
            FilledTonalButton(onClick = { showPopup = true }) {
                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(formatSpeed(playbackSpeed), style = MaterialTheme.typography.labelLarge)
            }

            if (showPopup) {
                Popup(
                    alignment = Alignment.BottomCenter,
                    onDismissRequest = { showPopup = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier.width(320.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Playback Speed", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(16.dp))

                            Text(
                                formatSpeed(sliderSpeed),
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )

                            Spacer(Modifier.height(20.dp))

                            Slider(
                                value = sliderSpeed,
                                onValueChange = { sliderSpeed = snapSpeed(it) },
                                onValueChangeFinished = { onSpeedClick(sliderSpeed) },
                                valueRange = MIN_SPEED..MAX_SPEED,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "${MIN_SPEED}x",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${MAX_SPEED}x",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Spacer(Modifier.height(20.dp))

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SPEED_PRESETS.forEach { preset ->
                                    val isSelected = (sliderSpeed - preset).absoluteValue < 0.01f
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            sliderSpeed = preset
                                            onSpeedClick(preset)
                                        },
                                        label = { Text(formatSpeed(preset)) },
                                    )
                                }
                            }

                            if ((sliderSpeed - 1.0f).absoluteValue > 0.01f) {
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = {
                                    sliderSpeed = 1.0f
                                    onSpeedClick(1.0f)
                                }) {
                                    Text("Reset to ${formatSpeed(1.0f)}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Helpers ---

private fun formatPlaybackTime(duration: Duration): String {
    val hours = duration.inWholeHours
    val minutes = duration.inWholeMinutes % 60
    val seconds = duration.inWholeSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

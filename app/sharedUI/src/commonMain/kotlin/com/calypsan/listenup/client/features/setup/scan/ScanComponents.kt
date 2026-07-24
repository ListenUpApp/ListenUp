package com.calypsan.listenup.client.features.setup.scan

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.api.event.ScanBookRef
import com.calypsan.listenup.client.design.components.BookCoverFallback
import com.calypsan.listenup.client.design.components.cookieScallopShape
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.scan_scanning
import org.jetbrains.compose.resources.stringResource

private const val OUTER_ROTATION_MS = 9_000
private const val INNER_ROTATION_MS = 7_000

/** Lazy leftward drift speed for the cover strip — deliberately decoupled from scan throughput. */
private val MARQUEE_SPEED_DP_PER_SECOND = 22.dp
private const val CORE_PULSE_MS = 1_100
private const val FILE_LINE_PULSE_MS = 700
private const val PATH_TAIL_LENGTH = 48

/**
 * The hero scan animation — three concentric [cookieScallopShape] scallops behind a soft radial
 * brand glow: an outer container scallop rotating one way, an inner tertiary scallop counter-rotating,
 * and a pulsing brand-coloured core holding the [Icons.Rounded.GraphicEq] glyph. Purely decorative;
 * communicates "work is happening" without claiming a specific progress value.
 */
@Composable
fun ScanLoader(
    modifier: Modifier = Modifier,
    size: Dp = 146.dp,
) {
    val transition = rememberInfiniteTransition(label = "scanLoader")
    val outerRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(OUTER_ROTATION_MS, easing = LinearEasing)),
        label = "outer",
    )
    val innerRotation by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(INNER_ROTATION_MS, easing = LinearEasing)),
        label = "inner",
    )
    val corePulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(CORE_PULSE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size * 0.92f)
                .background(
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .size(size)
                .rotate(outerRotation)
                .clip(cookieScallopShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        Box(
            Modifier
                .size(size * 0.72f)
                .rotate(innerRotation)
                .clip(cookieScallopShape())
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f)),
        )
        Box(
            Modifier
                .size(size * 0.46f)
                .scale(corePulse)
                .clip(cookieScallopShape())
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.22f),
            )
        }
    }
}

/**
 * A single live scan statistic — brand icon, a big thousands-grouped [value] that animates as it
 * climbs (via [animateIntAsState]), and an uppercase [label]. Three of these sit side-by-side on the
 * scan screen for Books / Authors / Hours.
 */
@Composable
fun StatChip(
    icon: ImageVector,
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val shown by animateIntAsState(targetValue = value, label = "stat-$label")
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(vertical = 12.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(
            text = shown.formatGrouped(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.6.sp,
        )
    }
}

/**
 * A growing strip of [BookCoverFallback] tiles for the books matched so far, newest at the end. The
 * row drifts leftward at a constant, lazy pace (see [MARQUEE_SPEED_DP_PER_SECOND]) — independent of
 * how fast the [books] list grows — so covers glide off the left while newer ones wait their turn at
 * the right; it never loops or resets, and a fast scan simply outruns it without making it race. Each
 * tile is keyed by the book it shows, so an already-displayed title keeps its exact place and contents;
 * a newly-scanned book is appended, never swapped in over an existing tile. Covers aren't available
 * mid-scan, so each tile is a deterministic title/author placeholder. The edges fade via a
 * [BlendMode.DstIn] gradient mask. Renders nothing when [books] is empty.
 */
@Composable
fun ScanCoversMarquee(
    books: List<ScanBookRef>,
    modifier: Modifier = Modifier,
    tile: Dp = 54.dp,
) {
    if (books.isEmpty()) return
    val listState = rememberLazyListState()
    // Drift the strip leftward at a constant, lazy pace, decoupled from how fast books arrive. A fast
    // scan appends covers far quicker than the eye wants to track; chasing the tail on every append
    // makes the row race. Instead we creep toward the tail at a fixed speed and simply fall behind —
    // catching up before the scan finishes doesn't matter, the gentle motion is the point. When the
    // strip is caught up there's nothing left to reveal, so it idles until the next book lands.
    val density = LocalDensity.current
    LaunchedEffect(listState, density) {
        val pixelsPerSecond = with(density) { MARQUEE_SPEED_DP_PER_SECOND.toPx() }
        var lastFrameNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = (frameNanos - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = frameNanos
            if (listState.canScrollForward) {
                listState.scrollBy(pixelsPerSecond * elapsedSeconds)
            }
        }
    }
    val fade =
        Brush.horizontalGradient(
            0f to Color.Transparent,
            0.08f to Color.Black,
            0.92f to Color.Black,
            1f to Color.Transparent,
        )
    LazyRow(
        state = listState,
        modifier =
            modifier
                .fillMaxWidth()
                .height(tile)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = fade, blendMode = BlendMode.DstIn)
                },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = false,
    ) {
        items(items = books, key = { "${it.title} ${it.author}" }) { book ->
            BookCoverFallback(
                title = book.title,
                author = book.author,
                modifier = Modifier.size(tile),
                seed = book.title + book.author,
            )
        }
    }
}

/**
 * The "currently scanning" pill — a pulsing brand dot, a static "SCANNING" label, and the path being
 * analyzed in monospace, showing the path *tail* (filename end) so the meaningful part stays visible.
 * Pass `null` for [file] to render an empty pill (no path yet).
 */
@Composable
fun ScanFileLine(
    file: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val transition = rememberInfiniteTransition(label = "scanDot")
        val dotAlpha by transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(FILE_LINE_PULSE_MS), RepeatMode.Reverse),
            label = "dotAlpha",
        )
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha)),
        )
        Text(
            text = stringResource(Res.string.scan_scanning),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = file.orEmpty().takeLast(PATH_TAIL_LENGTH),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Groups a non-negative integer into comma-separated thousands (e.g. `1647` → `"1,647"`). */
private fun Int.formatGrouped(): String =
    toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()

package com.calypsan.listenup.client.design.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.calypsan.listenup.client.design.motion.LocalReduceMotion
import com.calypsan.listenup.client.design.motion.LocalTouchExplorationActive
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * A single cover in a [FannedDeck]. Minimal descriptor so the design layer stays
 * decoupled from domain list models — call sites map their books into this.
 */
data class FannedDeckCover(
    val bookId: String,
    val coverPath: String?,
    val title: String,
    val author: String?,
    val coverHash: String? = null,
)

/**
 * The signature M3 Expressive "fanned deck" of square book covers — the front cover is
 * full-size and the rest fan out to the trailing edge, each progressively smaller and offset.
 *
 * Each tile uses the square [BookCoverImage] (with the gradient fallback on miss), so the deck
 * always reads as covers even before art downloads.
 *
 * When [animate] is true the deck continuously cycles its covers with a springy shift — the
 * front cover eases to the back and the rest advance, looping through every cover. A randomized
 * start index + stagger keeps multiple decks on a screen from moving in lockstep. The cycle pauses
 * under Remove animations and TalkBack (see [rememberDeckFront]).
 *
 * Renders nothing when [covers] is empty. Its measured width is `size + peek * (visible - 1)`
 * where `visible = min(max, covers.size)`; its height is [size].
 *
 * @param size edge length of the front (largest) cover
 * @param peek horizontal reveal between successive covers
 * @param max maximum number of covers visible at once
 * @param animate cycle the covers with a shifting animation
 * @param cycleMillis dwell time between shifts when [animate] is true
 */
@Composable
fun FannedDeck(
    covers: List<FannedDeckCover>,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    peek: Dp = 22.dp,
    max: Int = 4,
    animate: Boolean = false,
    cycleMillis: Long = 2800L,
) {
    if (covers.isEmpty()) return
    val count = covers.size
    val visible = minOf(max, count)
    val totalWidth = size + peek * (visible - 1)

    val frontIndex = rememberDeckFront(covers, animate, cycleMillis)

    val motion = MaterialTheme.motionScheme
    Box(modifier = modifier.size(width = totalWidth, height = size)) {
        covers.forEachIndexed { index, cover ->
            // depth: 0 = front, increasing = further back; covers past `visible` park hidden at the back.
            val depth = (index - frontIndex + count) % count
            val slot = depth.coerceAtMost(visible - 1)

            val animX by animateDpAsState(
                targetValue = peek * slot,
                animationSpec = motion.slowSpatialSpec(),
                label = "deckX",
            )
            val animScale by animateFloatAsState(
                targetValue = 1f - slot * SCALE_STEP,
                animationSpec = motion.slowSpatialSpec(),
                label = "deckScale",
            )
            val animAlpha by animateFloatAsState(
                targetValue = if (depth < visible) 1f else 0f,
                animationSpec = motion.slowEffectsSpec(),
                label = "deckAlpha",
            )

            Box(
                modifier =
                    Modifier
                        .zIndex((count - depth).toFloat())
                        .offset(x = animX)
                        .graphicsLayer {
                            scaleX = animScale
                            scaleY = animScale
                            alpha = animAlpha
                        }.size(size)
                        .shadow(8.dp, RoundedCornerShape(CORNER))
                        .border(2.dp, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(CORNER))
                        .clip(RoundedCornerShape(CORNER)),
            ) {
                BookCoverImage(
                    bookId = cover.bookId,
                    coverPath = cover.coverPath,
                    contentDescription = cover.title,
                    title = cover.title,
                    author = cover.author,
                    coverHash = cover.coverHash,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
            }
        }
    }
}

/**
 * Which cover is at the front of a [FannedDeck]: fixed at 0 when static, and cycling every
 * [cycleMillis] when [animate] is on.
 *
 * The cycle is ambient motion nobody asked for, so it holds still whenever it could get in the way:
 * when animations are removed ([LocalReduceMotion], WCAG 2.2.2), while TalkBack explores by touch
 * ([LocalTouchExplorationActive]) since moving covers would shift its focus, and while the screen is
 * not started. A card scrolled out of a lazy list leaves composition, which stops it too.
 */
@Composable
internal fun rememberDeckFront(
    covers: List<FannedDeckCover>,
    animate: Boolean,
    cycleMillis: Long,
): Int {
    val count = covers.size
    var frontIndex by remember(covers) { mutableIntStateOf(if (animate) Random.nextInt(count) else 0) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val cycling =
        animate &&
            count > 1 &&
            !LocalReduceMotion.current &&
            !LocalTouchExplorationActive.current &&
            lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    if (cycling) {
        val staggerMillis = remember(covers) { Random.nextLong(0L, MAX_STAGGER_MS) }
        LaunchedEffect(covers, cycleMillis) {
            delay(staggerMillis)
            while (true) {
                delay(cycleMillis)
                frontIndex = (frontIndex + 1) % count
            }
        }
    }
    return frontIndex
}

/** Upper bound of the random start delay that keeps decks on one screen out of lockstep. */
private const val MAX_STAGGER_MS = 500L

/** A fanned stack of small covers: a tighter radius than the 24dp cover-tile token so small covers stay crisp. */
private val CORNER = 14.dp

/** Each successive cover shrinks by this fraction. */
private const val SCALE_STEP = 0.07f

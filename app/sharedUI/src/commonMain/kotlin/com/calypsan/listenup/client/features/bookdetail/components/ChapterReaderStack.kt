package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.motion.rememberAmbientLoop
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaderFace
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaders
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReadership
import com.calypsan.listenup.client.presentation.bookdetail.ReaderClause
import com.calypsan.listenup.client.presentation.bookdetail.ReaderClauseForm
import com.calypsan.listenup.client.presentation.bookdetail.ReadersPlace
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_chapter_readers_earlier_many
import listenup.composeapp.generated.resources.book_detail_chapter_readers_earlier_many_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_earlier_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_earlier_two
import listenup.composeapp.generated.resources.book_detail_chapter_readers_further_on_many
import listenup.composeapp.generated.resources.book_detail_chapter_readers_further_on_many_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_further_on_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_further_on_two
import listenup.composeapp.generated.resources.book_detail_chapter_readers_in_part_many
import listenup.composeapp.generated.resources.book_detail_chapter_readers_in_part_many_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_in_part_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_in_part_two
import listenup.composeapp.generated.resources.book_detail_chapter_readers_listening_now_many
import listenup.composeapp.generated.resources.book_detail_chapter_readers_listening_now_many_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_listening_now_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_listening_now_two
import listenup.composeapp.generated.resources.book_detail_chapter_readers_on_chapter_many
import listenup.composeapp.generated.resources.book_detail_chapter_readers_on_chapter_many_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_on_chapter_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_on_chapter_two
import listenup.composeapp.generated.resources.book_detail_chapter_readers_other_chapters_many
import listenup.composeapp.generated.resources.book_detail_chapter_readers_other_chapters_many_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_other_chapters_one
import listenup.composeapp.generated.resources.book_detail_chapter_readers_other_chapters_two
import org.jetbrains.compose.resources.stringResource

/**
 * Where everyone else is in the book, as the chapter list draws it.
 *
 * @property readership Which row, part header and "All N chapters" button carries whose avatar.
 * @property isAnyoneListening Someone else is listening to this book right now: only then does the glow's clock run.
 */
internal data class ChapterReadersOnPage(
    val readership: ChapterReadership,
    val isAnyoneListening: Boolean,
) {
    /** Holds [None]. */
    companion object {
        /** Nobody else on any chapter: the list draws no avatars. */
        val None = ChapterReadersOnPage(ChapterReadership.None, isAnyoneListening = false)
    }
}

/** The stack's faces: the board's 24 dp, so the row keeps its height. */
private val FaceSize = AvatarSize.Compact

/** How far each face tucks under the one before it. */
private val FaceOverlap = 6.dp

/** A face after a live one tucks in only this far, so the live ring stays whole. */
private val AfterLiveOverlap = 2.dp

/** The cut around every face, in the colour of the row it sits on. */
private val CutWidth = 2.dp

/** The live ring's outer edge: 2 dp of coral beyond the cut. */
private val LiveRingOuter = 4.dp

/** How long the live ring takes to come and go when someone starts or stops listening. */
private const val RING_FADE_MS = 200

/** One breath of the glow and the rest after it. */
private const val GLOW_CYCLE_MS = 2_000

/** The breath itself; the rest of the cycle holds it out, faded. */
private const val GLOW_BREATH_MS = 1_600f

private const val GLOW_FROM_SCALE = 1f
private const val GLOW_TO_SCALE = 1.7f
private const val GLOW_FROM_ALPHA = 0.45f

/** The +N disc's text, as a share of its diameter, so it fits at any font scale. */
private const val DISC_TEXT_RATIO = 0.4f

/** The clock position that means "held still": motion is off. */
internal const val STILL_GLOW = -1f

/** M3's emphasized decelerate: a quick swell that settles out. */
private val EmphasizedDecelerate = CubicBezierEasing(a = 0.05f, b = 0.7f, c = 0.1f, d = 1f)

/** M3's standard easing, for the ring's fade. */
private val StandardEasing = CubicBezierEasing(a = 0.2f, b = 0f, c = 0f, d = 1f)

/**
 * The glow behind a live avatar at one moment.
 *
 * @property scale Its diameter as a multiple of the avatar's.
 * @property alpha Its opacity.
 */
internal data class LiveGlowPose(
    val scale: Float,
    val alpha: Float,
) {
    /** Holds [Still]. */
    companion object {
        /** The glow with motion off: still, a little larger than the ring, faint. */
        val Still = LiveGlowPose(scale = 1.35f, alpha = 0.22f)
    }
}

/** The glow [cycleMs] into its cycle, or [LiveGlowPose.Still] at [STILL_GLOW]. Pure. */
internal fun liveGlowPoseAt(cycleMs: Float): LiveGlowPose {
    if (cycleMs < 0f) return LiveGlowPose.Still
    val breath = EmphasizedDecelerate.transform((cycleMs / GLOW_BREATH_MS).coerceIn(0f, 1f))
    return LiveGlowPose(
        scale = GLOW_FROM_SCALE + (GLOW_TO_SCALE - GLOW_FROM_SCALE) * breath,
        alpha = GLOW_FROM_ALPHA * (1f - breath),
    )
}

/** The screen's one glow clock, so every live avatar breathes in step; null where none runs. */
private val LocalLiveGlowClock = staticCompositionLocalOf<State<Float>?> { null }

/**
 * Runs one glow clock for [content] while [isAnyoneListening], so every live avatar under it breathes together. With
 * animations removed the clock holds at [STILL_GLOW], since an infinite transition would not stop on its own.
 */
@Composable
internal fun ProvideLiveGlow(
    isAnyoneListening: Boolean,
    content: @Composable () -> Unit,
) {
    val clock =
        if (isAnyoneListening) {
            rememberAmbientLoop(
                initialValue = 0f,
                targetValue = GLOW_CYCLE_MS.toFloat(),
                animationSpec = infiniteRepeatable(tween(GLOW_CYCLE_MS, easing = LinearEasing)),
                restValue = STILL_GLOW,
                label = "live glow",
            )
        } else {
            null
        }
    CompositionLocalProvider(LocalLiveGlowClock provides clock, content = content)
}

/**
 * Overlapping avatars of the people on one row: up to three faces, or two and a "+N" disc, each cut out of the row by
 * a ring in [ringColor]. Someone listening now wears a still coral ring with a glow breathing behind it. Decorative:
 * not clickable and hidden from screen readers, as the row's own description says who is here.
 *
 * @param ringColor The colour of the row the stack sits on.
 */
@Composable
internal fun ChapterReaderStack(
    readers: ChapterReaders,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    val faces = readers.faces
    val circles = faces.size + if (readers.overflowCount > 0) 1 else 0
    Row(
        modifier = modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(-FaceOverlap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        faces.forEachIndexed { index, face ->
            // The first face sits on top, so each one after it reads as tucked beneath.
            val stacking = Modifier.zIndex((circles - index).toFloat())
            val afterLive = faces.getOrNull(index - 1)?.isLive == true
            key(face.userId) {
                StackFace(
                    face = face,
                    ringColor = ringColor,
                    modifier = if (afterLive) stacking.padding(start = FaceOverlap - AfterLiveOverlap) else stacking,
                )
            }
        }
        if (readers.overflowCount > 0) {
            OverflowDisc(count = readers.overflowCount, ringColor = ringColor, modifier = Modifier.zIndex(1f))
        }
    }
}

@Composable
private fun StackFace(
    face: ChapterReaderFace,
    ringColor: Color,
    modifier: Modifier,
) {
    val clock = LocalLiveGlowClock.current
    val coral = MaterialTheme.colorScheme.primary
    val live by animateFloatAsState(
        targetValue = if (face.isLive) 1f else 0f,
        animationSpec = tween(RING_FADE_MS, easing = StandardEasing),
        label = "live ring",
    )
    UserAvatar(
        userId = face.userId,
        size = FaceSize,
        fallbackName = face.displayName,
        // Drawn behind the face and past its edge; read in the draw phase, so the breath never recomposes.
        modifier =
            modifier.drawBehind {
                val radius = size.minDimension / 2
                if (live > 0f) {
                    val pose = liveGlowPoseAt(clock?.value ?: STILL_GLOW)
                    drawCircle(coral, radius = radius * pose.scale, alpha = pose.alpha * live)
                    drawCircle(coral, radius = radius + LiveRingOuter.toPx(), alpha = live)
                }
                drawCircle(ringColor, radius = radius + CutWidth.toPx())
            },
    )
}

@Composable
private fun OverflowDisc(
    count: Int,
    ringColor: Color,
    modifier: Modifier,
) {
    val fontSize = with(LocalDensity.current) { (FaceSize.dp.toPx() * DISC_TEXT_RATIO).toSp() }
    Box(
        modifier =
            modifier
                .size(FaceSize.dp)
                .drawBehind { drawCircle(ringColor, radius = size.minDimension / 2 + CutWidth.toPx()) }
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+$count",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private val ListeningNowKeys =
    ClauseKeys(
        one = Res.string.book_detail_chapter_readers_listening_now_one,
        two = Res.string.book_detail_chapter_readers_listening_now_two,
        manyOne = Res.string.book_detail_chapter_readers_listening_now_many_one,
        many = Res.string.book_detail_chapter_readers_listening_now_many,
    )

private val OnChapterKeys =
    ClauseKeys(
        one = Res.string.book_detail_chapter_readers_on_chapter_one,
        two = Res.string.book_detail_chapter_readers_on_chapter_two,
        manyOne = Res.string.book_detail_chapter_readers_on_chapter_many_one,
        many = Res.string.book_detail_chapter_readers_on_chapter_many,
    )

private val InPartKeys =
    ClauseKeys(
        one = Res.string.book_detail_chapter_readers_in_part_one,
        two = Res.string.book_detail_chapter_readers_in_part_two,
        manyOne = Res.string.book_detail_chapter_readers_in_part_many_one,
        many = Res.string.book_detail_chapter_readers_in_part_many,
    )

private val FurtherOnKeys =
    ClauseKeys(
        one = Res.string.book_detail_chapter_readers_further_on_one,
        two = Res.string.book_detail_chapter_readers_further_on_two,
        manyOne = Res.string.book_detail_chapter_readers_further_on_many_one,
        many = Res.string.book_detail_chapter_readers_further_on_many,
    )

private val EarlierKeys =
    ClauseKeys(
        one = Res.string.book_detail_chapter_readers_earlier_one,
        two = Res.string.book_detail_chapter_readers_earlier_two,
        manyOne = Res.string.book_detail_chapter_readers_earlier_many_one,
        many = Res.string.book_detail_chapter_readers_earlier_many,
    )

private val OtherChaptersKeys =
    ClauseKeys(
        one = Res.string.book_detail_chapter_readers_other_chapters_one,
        two = Res.string.book_detail_chapter_readers_other_chapters_two,
        manyOne = Res.string.book_detail_chapter_readers_other_chapters_many_one,
        many = Res.string.book_detail_chapter_readers_other_chapters_many,
    )

private val ReadersPlace.keys: ClauseKeys
    get() =
        when (this) {
            ReadersPlace.ThisChapter -> OnChapterKeys
            ReadersPlace.ThisPart -> InPartKeys
            ReadersPlace.FurtherOn -> FurtherOnKeys
            ReadersPlace.Earlier -> EarlierKeys
            ReadersPlace.OtherChapters -> OtherChaptersKeys
        }

@Composable
private fun ReaderClause.text(keys: ClauseKeys): String {
    val first = named.firstOrNull()?.firstName.orEmpty()
    val second = named.getOrNull(1)?.firstName.orEmpty()
    return when (form) {
        ReaderClauseForm.One -> stringResource(keys.one, first)
        ReaderClauseForm.Two -> stringResource(keys.two, first, second)
        ReaderClauseForm.TwoAndOneOther -> stringResource(keys.manyOne, first, second)
        ReaderClauseForm.TwoAndOthers -> stringResource(keys.many, first, second, othersCount)
    }
}

/**
 * What a screen reader says about a stack: who is listening now, then who else is here ("Maya is listening now. Priya,
 * Tom and 2 others are on this chapter."). Built from the catalog, never from ViewModel copy.
 */
@Composable
internal fun chapterReadersSentence(readers: ChapterReaders): String =
    listOfNotNull(
        readers.listeningNow?.text(ListeningNowKeys),
        readers.notListening?.text(readers.place.keys),
    ).joinToString(" ")

/** [label] with the readers' sentence after it, or just [label] when nobody else is here. */
@Composable
internal fun withReaders(
    label: String,
    readers: ChapterReaders?,
): String = readers?.let { "$label. ${chapterReadersSentence(it)}" } ?: label

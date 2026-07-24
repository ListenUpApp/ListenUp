package com.calypsan.listenup.client.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Data class representing the alphabet index for a list of items.
 */
data class AlphabetIndex(
    val letters: List<Char>,
    val letterToIndex: Map<Char, Int>,
) {
    companion object {
        fun <T> build(
            items: List<T>,
            keySelector: (T) -> String,
        ): AlphabetIndex {
            val letterToIndex = mutableMapOf<Char, Int>()

            items.forEachIndexed { index, item ->
                val key = keySelector(item)
                if (key.isNotEmpty()) {
                    val firstChar = key.first().uppercaseChar()
                    if (firstChar !in letterToIndex) {
                        letterToIndex[firstChar] = index
                    }
                }
            }

            // Sort: non-letters (#) first, then alphabetically
            val letters =
                letterToIndex.keys
                    .sortedWith(compareBy({ it.isLetter() }, { it }))

            return AlphabetIndex(letters, letterToIndex)
        }
    }
}

// =============================================================================
// Adaptive Sizing Configuration
// =============================================================================

/** Visible for testing. */
internal object AdaptiveScrollbarConfig {
    // Font size bounds (sp)
    const val MIN_FONT_SIZE_SP = 8f
    const val MAX_FONT_SIZE_SP = 14f

    // Font size as proportion of slot height
    const val FONT_SIZE_RATIO = 0.65f

    // Weight distribution for spatial breathing effect
    const val WEIGHT_SELECTED = 1.6f
    const val WEIGHT_DISTANCE_1 = 1.2f
    const val WEIGHT_DISTANCE_2 = 0.9f
    const val WEIGHT_DISTANT = 0.8f
    const val WEIGHT_AT_REST = 1.0f

    // Visual emphasis
    const val OPACITY_DISTANT = 0.85f
    const val SCALE_SELECTED = 1.3f

    // Padding inside the scrollbar column
    val VERTICAL_PADDING = 8.dp
    val HORIZONTAL_PADDING = 8.dp
}

/**
 * Calculates the weight for a letter based on its distance from the selected letter.
 * Weights determine how much vertical space each letter gets.
 *
 * Visible for testing.
 */
internal fun calculateWeight(
    index: Int,
    selectedIndex: Int?,
): Float {
    if (selectedIndex == null) return AdaptiveScrollbarConfig.WEIGHT_AT_REST

    return when (abs(index - selectedIndex)) {
        0 -> AdaptiveScrollbarConfig.WEIGHT_SELECTED
        1 -> AdaptiveScrollbarConfig.WEIGHT_DISTANCE_1
        2 -> AdaptiveScrollbarConfig.WEIGHT_DISTANCE_2
        else -> AdaptiveScrollbarConfig.WEIGHT_DISTANT
    }
}

/**
 * Google Contacts-style alphabet scrollbar with adaptive sizing.
 *
 * Design philosophy: Fill available space, breathe on interaction.
 * - Measures available height and distributes letters evenly to fit
 * - On interaction, space redistributes around selection (subtle fisheye)
 * - Material 3 Expressive springs for snappy, bouncy animations
 *
 * Features:
 * - Adaptive sizing that always fits available space
 * - Subtle spatial redistribution on touch (modern fisheye)
 * - Haptic feedback on letter changes
 * - Popup indicator for selected letter
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
@Composable
fun AlphabetScrollbar(
    alphabetIndex: AlphabetIndex,
    onLetterSelected: (itemIndex: Int) -> Unit,
    isScrolling: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (alphabetIndex.letters.isEmpty()) return

    val haptics = LocalHaptics.current
    val density = LocalDensity.current

    // Track container height for adaptive sizing
    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    // Track the actual center Y position of each letter (measured, not calculated)
    val letterCenterYs = remember { mutableStateMapOf<Int, Float>() }

    var isInteracting by remember { mutableStateOf(false) }
    var selectedLetter by remember { mutableStateOf<Char?>(null) }
    var selectedLetterIndex by remember { mutableStateOf<Int?>(null) }
    var lastHapticLetter by remember { mutableStateOf<Char?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var selectedLetterY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isScrolling, isInteracting) {
        if (isScrolling || isInteracting) {
            isVisible = true
        } else {
            delay(1500)
            isVisible = false
        }
    }

    // Calculate available height for letters (excluding padding)
    val availableHeightPx by remember(containerHeightPx) {
        derivedStateOf {
            val paddingPx =
                with(density) {
                    (AdaptiveScrollbarConfig.VERTICAL_PADDING * 2).toPx()
                }
            (containerHeightPx - paddingPx).coerceAtLeast(0f)
        }
    }

    // Calculate total weight for normalization
    val totalWeight by remember(selectedLetterIndex, alphabetIndex.letters.size) {
        derivedStateOf {
            alphabetIndex.letters.indices
                .sumOf { index ->
                    calculateWeight(index, selectedLetterIndex).toDouble()
                }.toFloat()
        }
    }

    // Snappy spring - fast and responsive, minimal bounce for polish
    val snappySpring =
        spring<Float>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessHigh,
        )

    val alpha by animateFloatAsState(
        targetValue =
            when {
                isInteracting -> 1f
                isVisible -> 0.9f
                else -> 0f
            },
        animationSpec = snappySpring,
        label = "alpha",
    )

    // Slide in/out from right edge - fast with subtle bounce
    val slideOffset by animateDpAsState(
        targetValue = if (isVisible || isInteracting) 0.dp else 48.dp,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessHigh,
            ),
        label = "slide",
    )

    /**
     * Find the letter closest to the given touch Y coordinate.
     * Uses actual measured positions, not calculated ones.
     */
    fun findLetterAtY(touchY: Float): Triple<Char, Int, Float>? {
        if (letterCenterYs.isEmpty()) return null

        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE

        letterCenterYs.forEach { (index, centerY) ->
            val distance = abs(touchY - centerY)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }

        if (bestIndex >= alphabetIndex.letters.size) return null

        val letter = alphabetIndex.letters[bestIndex]
        val centerY = letterCenterYs[bestIndex] ?: return null

        return Triple(letter, bestIndex, centerY)
    }

    fun selectLetter(
        y: Float,
        isInitialTouch: Boolean = false,
    ) {
        findLetterAtY(y)?.let { (letter, index, centerY) ->
            if (letter != selectedLetter) {
                selectedLetter = letter
                selectedLetterIndex = index
                selectedLetterY = centerY
                alphabetIndex.letterToIndex[letter]?.let(onLetterSelected)

                // Haptic feedback - stronger for initial touch, tick for subsequent
                if (isInitialTouch) {
                    haptics.longPress()
                } else if (letter != lastHapticLetter) {
                    haptics.selectionTick()
                }
                lastHapticLetter = letter
            }
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .offset(x = slideOffset)
                .alpha(alpha)
                .semantics {
                    contentDescription = "Alphabet scrollbar"
                    selectedLetter?.let { letter ->
                        stateDescription = "Letter $letter"
                    }
                }.onSizeChanged { size ->
                    containerHeightPx = size.height.toFloat()
                },
    ) {
        // Popup indicator - positioned at the selected letter's Y
        AnimatedVisibility(
            visible = isInteracting && selectedLetter != null,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            selectedLetter?.let { letter ->
                // Big Material scallop "cookie" scrub bubble (matches the design's LetterBubble).
                Box(
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(
                                    x = -88.dp.roundToPx(),
                                    y = (selectedLetterY - 36.dp.toPx()).toInt(),
                                )
                            }.size(72.dp)
                            .shadow(elevation = 12.dp, shape = cookieScallopShape())
                            .clip(cookieScallopShape())
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = letter.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        // The scrollbar column with adaptive letter sizing
        Column(
            modifier =
                Modifier
                    // Enforce 48dp min-width so the touch target meets the a11y minimum
                    // even on short letter lists. Visual appearance unchanged — the
                    // letters are centred inside the wider clip area.
                    .defaultMinSize(minWidth = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isInteracting) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f)
                        },
                    ).pointerInput(alphabetIndex) {
                        detectTapGestures(
                            onPress = { offset ->
                                isInteracting = true
                                selectLetter(offset.y, isInitialTouch = true)
                                tryAwaitRelease()
                                isInteracting = false
                                selectedLetter = null
                                selectedLetterIndex = null
                                lastHapticLetter = null
                            },
                        )
                    }.pointerInput(alphabetIndex) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isInteracting = true
                                selectLetter(offset.y, isInitialTouch = true)
                            },
                            onDragEnd = {
                                isInteracting = false
                                selectedLetter = null
                                selectedLetterIndex = null
                                lastHapticLetter = null
                            },
                            onDragCancel = {
                                isInteracting = false
                                selectedLetter = null
                                selectedLetterIndex = null
                                lastHapticLetter = null
                            },
                            onDrag = { change, _ ->
                                selectLetter(change.position.y)
                            },
                        )
                    }.padding(
                        horizontal = AdaptiveScrollbarConfig.HORIZONTAL_PADDING,
                        vertical = AdaptiveScrollbarConfig.VERTICAL_PADDING,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            alphabetIndex.letters.forEachIndexed { index, letter ->
                val weight = calculateWeight(index, selectedLetterIndex)
                val letterHeightPx =
                    if (totalWeight > 0) {
                        availableHeightPx * (weight / totalWeight)
                    } else {
                        availableHeightPx / alphabetIndex.letters.size
                    }
                val letterHeightDp = with(density) { letterHeightPx.toDp() }

                AdaptiveLetterItem(
                    letter = letter,
                    index = index,
                    selectedIndex = selectedLetterIndex,
                    height = letterHeightDp,
                    onPositionMeasured = { centerY ->
                        letterCenterYs[index] = centerY
                    },
                )
            }
        }
    }
}

/**
 * Individual letter with adaptive height and animated visual properties.
 *
 * Height is determined by weight distribution (subtle fisheye effect).
 * Visual emphasis through scale, opacity, color, and font weight.
 */
@Composable
private fun AdaptiveLetterItem(
    letter: Char,
    index: Int,
    selectedIndex: Int?,
    height: Dp,
    onPositionMeasured: (Float) -> Unit,
) {
    val density = LocalDensity.current

    // Animate height changes - fast spring for responsive feel
    val animatedHeight by animateDpAsState(
        targetValue = height,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh,
            ),
        label = "letterHeight",
    )

    // Calculate font size from slot height
    val fontSizeSp =
        with(density) {
            val heightSp = animatedHeight.toSp().value
            (heightSp * AdaptiveScrollbarConfig.FONT_SIZE_RATIO)
                .coerceIn(
                    AdaptiveScrollbarConfig.MIN_FONT_SIZE_SP,
                    AdaptiveScrollbarConfig.MAX_FONT_SIZE_SP,
                )
        }

    // Distance-based visual properties
    val distance = selectedIndex?.let { abs(index - it) }
    val isSelected = index == selectedIndex

    // Scale animation for selected letter
    val targetScale =
        when {
            selectedIndex == null -> 1f
            isSelected -> AdaptiveScrollbarConfig.SCALE_SELECTED
            else -> 1f
        }

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessHigh,
            ),
        label = "letterScale",
    )

    // Opacity for distant letters (dimming effect)
    val targetOpacity =
        when {
            selectedIndex == null -> 1f
            distance != null && distance <= 1 -> 1f
            else -> AdaptiveScrollbarConfig.OPACITY_DISTANT
        }

    val opacity by animateFloatAsState(
        targetValue = targetOpacity,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh,
            ),
        label = "letterOpacity",
    )

    // Font weight based on selection
    val fontWeight =
        when {
            isSelected -> FontWeight.Bold
            distance != null && distance == 1 -> FontWeight.Medium
            else -> FontWeight.Normal
        }

    // Color based on selection
    val color =
        if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Box(
        modifier =
            Modifier
                .height(animatedHeight)
                .onGloballyPositioned { coordinates ->
                    // Report center Y position relative to parent Column for hit detection
                    val posInParent = coordinates.positionInParent()
                    val centerY = posInParent.y + coordinates.size.height / 2f
                    onPositionMeasured(centerY)
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            fontSize = fontSizeSp.sp,
            fontWeight = fontWeight,
            color = color,
            modifier =
                Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = opacity
                    },
        )
    }
}

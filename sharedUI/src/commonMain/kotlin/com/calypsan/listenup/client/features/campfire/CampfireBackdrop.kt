package com.calypsan.listenup.client.features.campfire

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Escalation stage for [CampfireBackdrop] — Create/Invite render [EMBER], the Lobby renders
 * [STEADY], the live Room renders [ROARING] (co-listening design spec, 2026-07-11 lobby amendment,
 * task L3). Each stage scales the breathing-glow amplitude and rising-ember particle count.
 */
internal enum class CampfireBackdropStage {
    /** Calm, sparse embers — the pre-session Create/Invite screens. */
    EMBER,

    /** A settled, steady fire — the Lobby ("Warming up") screen. */
    STEADY,

    /** A lively, roaring fire — the live co-listening Room. */
    ROARING,
}

private data class StageSpec(
    val emberCount: Int,
    val glowMin: Float,
    val glowMax: Float,
    val glowPeriodMs: Int,
    val riseMs: Int,
)

private fun specFor(stage: CampfireBackdropStage): StageSpec =
    when (stage) {
        CampfireBackdropStage.EMBER -> {
            StageSpec(
                emberCount = 8,
                glowMin = 0.32f,
                glowMax = 0.5f,
                glowPeriodMs = 4200,
                riseMs = 6200,
            )
        }

        CampfireBackdropStage.STEADY -> {
            StageSpec(
                emberCount = 14,
                glowMin = 0.42f,
                glowMax = 0.72f,
                glowPeriodMs = 3200,
                riseMs = 4600,
            )
        }

        CampfireBackdropStage.ROARING -> {
            StageSpec(
                emberCount = 22,
                glowMin = 0.52f,
                glowMax = 0.95f,
                glowPeriodMs = 2200,
                riseMs = 3200,
            )
        }
    }

/**
 * Full-bleed night-sky-and-firelight backdrop for the Campfire flow (Create, Invite, Lobby, Room —
 * co-listening design spec, 2026-07-11 lobby amendment). A simplified v1: a dark radial-gradient
 * night scene, a breathing coral glow pooled at the bottom (an infinite transition, amplitude driven
 * by [stage]), and a lightweight rising-ember particle layer via [Canvas]. Deliberately NOT a full
 * flame simulation — that is explicitly deferred polish per the amendment.
 *
 * @param stage Escalation stage — see [CampfireBackdropStage].
 * @param reducedMotion When `true`, drops the particle layer entirely and slows the glow breathing
 * to a third of its normal cadence, honoring a reduced-motion preference.
 * @param content Screen content, rendered above the backdrop and its legibility scrim.
 */
@Composable
internal fun CampfireBackdrop(
    stage: CampfireBackdropStage,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val spec = remember(stage) { specFor(stage) }
    val glowTransition = rememberInfiniteTransition(label = "campfireGlow")
    val glow by glowTransition.animateFloat(
        initialValue = spec.glowMin,
        targetValue = spec.glowMax,
        animationSpec =
            infiniteRepeatable(
                animation =
                    tween(
                        if (reducedMotion) spec.glowPeriodMs * 3 else spec.glowPeriodMs,
                        easing = FastOutSlowInEasing,
                    ),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "campfireGlowAmplitude",
    )

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(CampfireFlowColors.NightBottom, CampfireFlowColors.NightTop),
                    ),
                ),
    ) {
        // Breathing firelight pool — the same amplitude value CSS glow layers used in the design
        // mockup, here driving a single radial-gradient box.
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.85f)
                    .height(260.dp)
                    .graphicsLayer { alpha = glow }
                    .background(
                        Brush.radialGradient(
                            colors = listOf(CampfireFlowColors.CoralBright.copy(alpha = 0.6f), Color.Transparent),
                        ),
                    ),
        )

        if (!reducedMotion) {
            EmberParticles(
                count = spec.emberCount,
                riseMs = spec.riseMs,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Legibility scrim: darkest at the top where header chrome sits, fading toward the fire glow.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                CampfireFlowColors.NightTop.copy(alpha = 0.88f),
                                CampfireFlowColors.NightTop.copy(alpha = 0.55f),
                                Color.Transparent,
                            ),
                        ),
                    ),
        )

        content()
    }
}

/**
 * A lightweight rising-ember layer: [count] particles, each looping up the screen over [riseMs] on
 * a shared [rememberInfiniteTransition] phase (offset per-particle so they don't move in lockstep),
 * with a small sinusoidal horizontal drift. Deterministic seeding ([Random] keyed by index) avoids
 * per-recomposition reseeding.
 */
@Composable
private fun EmberParticles(
    count: Int,
    riseMs: Int,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "campfireEmbers")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(riseMs, easing = LinearEasing)),
        label = "campfireEmberPhase",
    )
    val seeds =
        remember(count) {
            List(count) { index ->
                val random = Random(index * 7919 + 13)
                Triple(random.nextFloat(), random.nextFloat(), random.nextFloat())
            }
        }

    Canvas(modifier = modifier) {
        seeds.forEach { (xSeed, phaseOffset, driftSeed) ->
            val p = (phase + phaseOffset) % 1f
            val drift = sin((p * 2f * PI).toFloat() + driftSeed * 6f) * 10.dp.toPx()
            val x = (size.width * xSeed + drift).coerceIn(0f, size.width)
            val y = size.height * (1f - p)
            val alpha = (1f - p).coerceIn(0f, 1f) * 0.8f
            val radius = (1.4f + 2.2f * (1f - p)) * (1f + driftSeed * 0.4f)
            drawCircle(
                color = Color(0xFFFFB27A).copy(alpha = alpha),
                radius = radius.dp.toPx(),
                center = Offset(x, y),
            )
        }
    }
}

package com.calypsan.listenup.client.features.campfire

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.campfire.FireGeometry
import com.calypsan.listenup.client.campfire.computeFireGeometry
import com.calypsan.listenup.client.campfire.emberSparkColor
import com.calypsan.listenup.client.campfire.flameAlphaEnvelope
import com.calypsan.listenup.client.campfire.flameLickColor
import com.calypsan.listenup.client.campfire.flameRadiusScale
import com.calypsan.listenup.client.campfire.glowLayerCenteringOffsetPx
import com.calypsan.listenup.client.campfire.glowValue
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Escalation stage for [CampfireBackdrop] — Create/Invite render [EMBER], the Lobby renders
 * [STEADY], the live Room renders [ROARING] (co-listening design spec, 2026-07-11 lobby amendment,
 * task L3). Each stage scales the fire simulation's spawn rate, particle life/size, glow intensity,
 * and particle-pool caps — see [specFor].
 */
internal enum class CampfireBackdropStage {
    /** Calm, sparse licks over a deep coal bed — the pre-session Create/Invite screens. */
    EMBER,

    /** A settled, steady fire — the Lobby ("Warming up") screen. */
    STEADY,

    /** A lively, roaring fire — the live co-listening Room. */
    ROARING,
}

/**
 * Per-stage tuning, ported from the Campfire design reference's `FIRE_STYLES` table (`fire.jsx`).
 * `flameRate`/`sparkRate` scale spawn frequency, `intensity` scales overall flame size/velocity/glow
 * (`fire.jsx`'s `intensity` prop), `density` scales spawn volume independent of size (`fire.jsx`'s
 * `density` prop). `maxFlames`/`maxSparks` are hard particle-pool caps — the device-friendly bound
 * the reference doesn't need (an HTML canvas has no shared compositing budget with app UI); once a
 * pool is full, spawns are silently dropped until an existing particle dies and frees a slot, so the
 * cap reads as the fire's peak density rather than a hard cutoff.
 */
private data class StageSpec(
    val flameRate: Float,
    val flameVy: Float,
    val flameLifeScale: Float,
    val flameWidthScale: Float,
    val sparkRate: Float,
    val coalIntensity: Float,
    val intensity: Float,
    val density: Float,
    val maxFlames: Int,
    val maxSparks: Int,
    val glowMin: Float,
    val glowMax: Float,
    val glowPeriodMs: Int,
)

private fun specFor(stage: CampfireBackdropStage): StageSpec =
    when (stage) {
        CampfireBackdropStage.EMBER -> {
            StageSpec(
                flameRate = 0.42f,
                flameVy = 1.25f,
                flameLifeScale = 0.9f,
                flameWidthScale = 0.9f,
                sparkRate = 1.7f,
                coalIntensity = 1.5f,
                intensity = 0.55f,
                density = 0.5f,
                maxFlames = 14,
                maxSparks = 16,
                glowMin = 0.32f,
                glowMax = 0.5f,
                glowPeriodMs = 4200,
            )
        }

        CampfireBackdropStage.STEADY -> {
            StageSpec(
                flameRate = 1.0f,
                flameVy = 2.05f,
                flameLifeScale = 1.0f,
                flameWidthScale = 1.0f,
                sparkRate = 0.85f,
                coalIntensity = 1.0f,
                intensity = 0.85f,
                density = 0.85f,
                maxFlames = 22,
                maxSparks = 22,
                glowMin = 0.42f,
                glowMax = 0.72f,
                glowPeriodMs = 3200,
            )
        }

        CampfireBackdropStage.ROARING -> {
            StageSpec(
                flameRate = 1.35f,
                flameVy = 3.2f,
                flameLifeScale = 1.25f,
                flameWidthScale = 1.25f,
                sparkRate = 1.3f,
                coalIntensity = 0.9f,
                intensity = 1.3f,
                density = 1.35f,
                maxFlames = 40,
                maxSparks = 32,
                glowMin = 0.52f,
                glowMax = 1.05f,
                glowPeriodMs = 2200,
            )
        }
    }

/** Fraction of the canvas height where the fire's base (coal bed + logs) sits — `fire.jsx`'s default `baseY`. */
private const val CAMPFIRE_BASE_Y_FRACTION = 0.72f

/** Device-density-independent cap on the fire's half-width — `fire.jsx`'s `Math.min(W * 0.28, 138)`. */
private const val CAMPFIRE_MAX_BASE_WIDTH_DP = 138

/** Wide ambient-warmth glow layer height — re-centered on [FireGeometry.baseY], see [glowLayerCenteringOffsetPx]. */
private const val CAMPFIRE_FAR_GLOW_HEIGHT_DP = 340

/** Tight firelight-pool glow layer height — re-centered on [FireGeometry.baseY], see [glowLayerCenteringOffsetPx]. */
private const val CAMPFIRE_NEAR_GLOW_HEIGHT_DP = 260

/** Hard cap on star count regardless of screen area — keeps very large/tablet canvases cheap to draw. */
private const val CAMPFIRE_MAX_STARS = 90

private val CampfireStarColor = Color(0xFFCDDAF2)

private val CampfireFarGlowBrush =
    Brush.radialGradient(
        0f to Color(0xFFFF7828).copy(alpha = 0.4f),
        1f to Color(0xFFFF5A1E).copy(alpha = 0f),
    )

private val CampfireNearGlowBrush =
    Brush.radialGradient(
        0f to Color(0xFFFF963A).copy(alpha = 0.66f),
        0.52f to Color(0xFFFF6E28).copy(alpha = 0.14f),
        0.74f to Color(0xFFFF5A1E).copy(alpha = 0f),
    )

private val CampfireNightSkyBrush =
    Brush.verticalGradient(listOf(CampfireFlowColors.NightBottom, CampfireFlowColors.NightTop))

private val CampfireScrimBrush =
    Brush.verticalGradient(
        listOf(
            CampfireFlowColors.NightTop.copy(alpha = 0.88f),
            CampfireFlowColors.NightTop.copy(alpha = 0.55f),
            Color.Transparent,
        ),
    )

/**
 * Full-bleed night-sky-and-firelight backdrop for the Campfire flow (Create, Invite, Lobby, Room —
 * co-listening design spec, 2026-07-11 lobby amendment). A ported particle-system fire scene: a
 * twinkling starfield, a glowing coal bed, two crossed logs, and additive flame licks that
 * color-shift core-white → amber → orange → ember-red as they age, plus rising sparks — all driven
 * by a single flicker value ([CampfireFireEngine.glow]) that the ambient glow layers, coal bed, and
 * log embers breathe in sync to. Ported from the design reference's particle simulation (`fire.jsx`)
 * to Compose; see [specFor] for the `fireStyle`/`intensity`/`density` stage mapping.
 *
 * The simulation ticks once per frame via [withFrameNanos] inside [CampfireFireTicker] — the
 * frame-derived [CampfireFireEngine.glow] is the only Compose-`State`-backed value in the engine, so
 * a tick invalidates only the draw/layer phases that read it ([CampfireGlowLayers] and
 * [CampfireFireCanvas]), never composable recomposition. Per-particle fields are plain `var`s: once
 * one of those scopes is invalidated by a `glow` read, the whole scope's draw block re-executes and
 * picks up current particle positions for free.
 *
 * @param stage Escalation stage — see [CampfireBackdropStage].
 * @param reducedMotion When `true`, drops the particle layer and star twinkle entirely, keeping only
 * a slow glow-breathing animation (v1's reduced-motion behavior, unchanged) so the scene stays alive
 * without moving parts.
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
    val density = LocalDensity.current
    val maxBaseWidthPx = remember(density) { with(density) { CAMPFIRE_MAX_BASE_WIDTH_DP.dp.toPx() } }
    val engine =
        remember(stage) {
            CampfireFireEngine(
                spec.maxFlames,
                spec.maxSparks,
                seed =
                    stage.ordinal * 104_729L + 7L,
            )
        }
    val canvasSizeState = remember { mutableStateOf(IntSize.Zero) }
    val canvasSize = canvasSizeState.value
    val stars =
        remember(canvasSize, stage) {
            generateStarField(
                canvasSize,
                seed =
                    stage.ordinal * 7919L + 13L,
            )
        }
    val reducedGlow = rememberCampfireReducedGlow(spec)
    // Computed once per resize (not per frame) so the ambient-glow layers below can re-center on the
    // exact same origin the per-frame ticker/canvas geometry uses — see [CampfireGlowLayers].
    val geometry =
        remember(canvasSize, maxBaseWidthPx) {
            computeFireGeometry(
                widthPx = canvasSize.width.toFloat(),
                heightPx = canvasSize.height.toFloat(),
                baseYFraction = CAMPFIRE_BASE_Y_FRACTION,
                maxBaseWidthPx = maxBaseWidthPx,
            )
        }

    CampfireFireTicker(stage, reducedMotion, spec, engine, canvasSizeState, maxBaseWidthPx)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { canvasSizeState.value = it }
                .background(CampfireNightSkyBrush),
    ) {
        CampfireGlowLayers(reducedMotion, reducedGlow, engine, geometry, canvasSize.height.toFloat())
        CampfireFireCanvas(reducedMotion, reducedGlow, engine, stars, spec.coalIntensity, maxBaseWidthPx)

        // Legibility scrim: darkest at the top where header chrome sits, fading toward the fire glow.
        Box(modifier = Modifier.fillMaxSize().background(CampfireScrimBrush))

        content()
    }
}

/** v1's plain breathing-glow amplitude — the reduced-motion fallback, unchanged from before this upgrade. */
@Composable
private fun rememberCampfireReducedGlow(spec: StageSpec): Float {
    val transition = rememberInfiniteTransition(label = "campfireReducedGlow")
    val glow by transition.animateFloat(
        initialValue = spec.glowMin,
        targetValue = spec.glowMax,
        animationSpec =
            infiniteRepeatable(
                animation = tween(spec.glowPeriodMs * 3, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "campfireReducedGlowAmplitude",
    )
    return glow
}

/** Drives [engine] once per frame via [withFrameNanos]; a no-op while [reducedMotion] is `true`. */
@Composable
private fun CampfireFireTicker(
    stage: CampfireBackdropStage,
    reducedMotion: Boolean,
    spec: StageSpec,
    engine: CampfireFireEngine,
    canvasSizeState: MutableState<IntSize>,
    maxBaseWidthPx: Float,
) {
    if (reducedMotion) return
    LaunchedEffect(stage) {
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L) {
                    val elapsedNanos = frameNanos - lastFrameNanos
                    val dtFrames = (elapsedNanos / 16_666_667.0).toFloat().coerceIn(0f, 2.2f)
                    val canvasSize = canvasSizeState.value
                    val geometry =
                        computeFireGeometry(
                            widthPx = canvasSize.width.toFloat(),
                            heightPx = canvasSize.height.toFloat(),
                            baseYFraction = CAMPFIRE_BASE_Y_FRACTION,
                            maxBaseWidthPx = maxBaseWidthPx,
                        )
                    engine.tick(dtFrames, spec, geometry)
                }
                lastFrameNanos = frameNanos
            }
        }
    }
}

/**
 * The two ambient firelight washes, breathing in sync with [engine]'s (or [reducedGlow]'s) glow
 * value. Both are `Alignment.BottomCenter` layers of a fixed height, which by default center
 * themselves relative to the *canvas* bottom edge — that drifts away from [geometry]'s `baseY`
 * (the same origin the coal bed, logs, and flame spawn all share) whenever `baseYFraction` isn't
 * exactly the canvas midpoint, producing a detached glow "orb" below the fire instead of one light
 * source radiating from it. The `offset` on each layer corrects for that, via
 * [glowLayerCenteringOffsetPx], so both washes are co-located on the fire's origin by construction.
 */
@Composable
private fun BoxScope.CampfireGlowLayers(
    reducedMotion: Boolean,
    reducedGlow: Float,
    engine: CampfireFireEngine,
    geometry: FireGeometry,
    canvasHeightPx: Float,
) {
    // Wide ambient warmth — a soft wash behind the fire proper, centered on the fire's own origin.
    Box(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(CAMPFIRE_FAR_GLOW_HEIGHT_DP.dp)
                .offset {
                    val layerHeightPx = CAMPFIRE_FAR_GLOW_HEIGHT_DP.dp.toPx()
                    val offsetY = glowLayerCenteringOffsetPx(geometry.baseY, canvasHeightPx, layerHeightPx)
                    IntOffset(0, offsetY.roundToInt())
                }.graphicsLayer {
                    val gv = if (reducedMotion) reducedGlow else engine.glow
                    val farAlphaWeight = 0.34f
                    val farAlphaBaseline = 0.14f
                    alpha = (farAlphaWeight * gv + farAlphaBaseline).coerceIn(0f, 1f)
                }.background(CampfireFarGlowBrush),
    )
    // Tight firelight pool — the same origin, the same amplitude value drives opacity and a subtle
    // breathing scale.
    Box(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.85f)
                .height(CAMPFIRE_NEAR_GLOW_HEIGHT_DP.dp)
                .offset {
                    val layerHeightPx = CAMPFIRE_NEAR_GLOW_HEIGHT_DP.dp.toPx()
                    val offsetY = glowLayerCenteringOffsetPx(geometry.baseY, canvasHeightPx, layerHeightPx)
                    IntOffset(0, offsetY.roundToInt())
                }.graphicsLayer {
                    val gv = if (reducedMotion) reducedGlow else engine.glow
                    val nearAlphaWeight = 0.55f
                    val nearAlphaBaseline = 0.28f
                    alpha = (nearAlphaWeight * gv + nearAlphaBaseline).coerceIn(0f, 1f)
                    val breathBaseline = 0.94f
                    val breathWeight = 0.12f
                    val breathScale = breathBaseline + gv * breathWeight
                    scaleX = breathScale
                    scaleY = breathScale
                }.background(CampfireNearGlowBrush),
    )
}

/** The fire proper: starfield, coal bed, logs, and (full-motion only) flame licks and sparks. */
@Composable
private fun CampfireFireCanvas(
    reducedMotion: Boolean,
    reducedGlow: Float,
    engine: CampfireFireEngine,
    stars: List<StarSeed>,
    coalIntensity: Float,
    maxBaseWidthPx: Float,
) {
    Canvas(
        // Offscreen compositing isolates the additive (Plus) flame/spark blending to this layer's
        // own draws (stars → coal → logs, then flames/sparks on top) — mirroring the reference's
        // canvas raster, which additively blends only against its own prior draws before the whole
        // image is laid normally over the page.
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        val gv = if (reducedMotion) reducedGlow else engine.glow
        val nowMs = if (reducedMotion) 0f else engine.elapsedMs
        val geometry =
            computeFireGeometry(
                widthPx = size.width,
                heightPx = size.height,
                baseYFraction = CAMPFIRE_BASE_Y_FRACTION,
                maxBaseWidthPx = maxBaseWidthPx,
            )
        drawStars(stars, reducedMotion, nowMs)
        drawCoalBed(geometry, coalIntensity, gv)
        drawLogs(geometry, gv)
        if (!reducedMotion) {
            engine.forEachActiveFlame { drawFlame(it) }
            engine.forEachActiveSpark { drawSpark(it) }
        }
    }
}

/** One rising ember/spark's per-particle state — plain `var`s, reused via [CampfireFireEngine]'s fixed pool. */
private class SparkParticle {
    var active = false
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var age = 0f
    var lifespan = 1f
    var radius = 0f
    var wobblePhase = 0f
    var wobbleSpeed = 0f
    var twinkleSpeed = 0f
}

/** One flame lick's per-particle state — plain `var`s, reused via [CampfireFireEngine]'s fixed pool. */
private class FlameParticle {
    var active = false
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var age = 0f
    var lifespan = 1f
    var baseRadius = 0f
    var wobblePhase = 0f
    var wobbleSpeed = 0f
}

/**
 * The Campfire fire simulation: a fixed pool of [FlameParticle]s and [SparkParticle]s (no
 * per-frame allocation once warmed up — dead particles are reused in place) plus the flicker
 * low-pass filter that produces [glow]. Ported from `fire.jsx`'s per-frame `frame()` closure.
 *
 * [glow] is the only field backed by Compose `State` (see [CampfireBackdrop]'s KDoc for why);
 * everything else is a plain field read from within whichever draw/layer scope [glow] invalidates.
 */
private class CampfireFireEngine(
    maxFlames: Int,
    maxSparks: Int,
    seed: Long,
) {
    var glow: Float by mutableFloatStateOf(0.7f)
        private set

    /** Elapsed simulation time in milliseconds — drives star twinkle phase, mirroring `fire.jsx`'s `now`. */
    var elapsedMs: Float = 0f
        private set

    private val random = Random(seed)
    private val flames = Array(maxFlames) { FlameParticle() }
    private val sparks = Array(maxSparks) { SparkParticle() }

    private var flick = 0.7f
    private var flickTarget = 0.7f
    private var flickTimer = 0f
    private var flameSpawnCarry = 0f
    private var sparkSpawnCarry = 0f

    fun tick(
        dtFrames: Float,
        spec: StageSpec,
        geometry: FireGeometry,
    ) {
        val msPerFrame = 1000f / 60f
        elapsedMs += dtFrames * msPerFrame

        flickTimer -= dtFrames
        if (flickTimer <= 0f) {
            val nextFlickTarget = randomInRange(random, 0.62f, 1.0f)
            val nextFlickTimer = randomInRange(random, 3f, 9f)
            flickTarget = nextFlickTarget
            flickTimer = nextFlickTimer
        }
        val flickLerpRate = 0.08f
        val flickDelta = (flickTarget - flick) * flickLerpRate * dtFrames
        flick += flickDelta
        val jitterRange = 0.05f
        val jitter = (random.nextFloat() - 0.5f) * jitterRange
        glow = glowValue(flick, jitter, spec.intensity)

        val flameSpawnRateScale = 3.4f
        val flameSpawnRate = spec.flameRate * spec.intensity * spec.density * flameSpawnRateScale * dtFrames
        flameSpawnCarry += flameSpawnRate
        while (flameSpawnCarry >= 1f) {
            spawnFlame(spec, geometry)
            flameSpawnCarry -= 1f
        }

        val sparkSpawnRateScale = 0.5f
        val sparkSpawnRate = spec.sparkRate * spec.density * sparkSpawnRateScale * dtFrames
        sparkSpawnCarry += sparkSpawnRate
        while (sparkSpawnCarry >= 1f) {
            spawnSpark(spec, geometry)
            sparkSpawnCarry -= 1f
        }

        val flameVyNearDeathDamping = 0.25f
        val flameVyDecayRate = 0.004f
        val flameWobbleAmplitude = 0.5f
        for (flame in flames) {
            if (!flame.active) continue
            flame.age += dtFrames
            if (flame.age >= flame.lifespan) {
                flame.active = false
                continue
            }
            val t = flame.age / flame.lifespan
            val wobbleDx = sin(flame.wobblePhase + flame.age * flame.wobbleSpeed) * flameWobbleAmplitude
            val dx = (flame.vx + wobbleDx) * dtFrames
            flame.x += dx
            val dy = flame.vy * dtFrames * (1f - t * flameVyNearDeathDamping)
            flame.y += dy
            val vyDecay = 1f - flameVyDecayRate * dtFrames
            flame.vy *= vyDecay
        }

        val sparkDespawnY = -20f
        val sparkVyDecayRate = 0.002f
        val sparkWobbleAmplitude = 0.6f
        for (spark in sparks) {
            if (!spark.active) continue
            spark.age += dtFrames
            if (spark.age >= spark.lifespan || spark.y < sparkDespawnY) {
                spark.active = false
                continue
            }
            val wobbleDx = sin(spark.wobblePhase + spark.age * spark.wobbleSpeed) * sparkWobbleAmplitude
            val dx = (spark.vx + wobbleDx) * dtFrames
            spark.x += dx
            val dy = spark.vy * dtFrames
            spark.y += dy
            val vyDecay = 1f - sparkVyDecayRate * dtFrames
            spark.vy *= vyDecay
        }
    }

    inline fun forEachActiveFlame(action: (FlameParticle) -> Unit) {
        for (flame in flames) if (flame.active) action(flame)
    }

    inline fun forEachActiveSpark(action: (SparkParticle) -> Unit) {
        for (spark in sparks) if (spark.active) action(spark)
    }

    private fun spawnFlame(
        spec: StageSpec,
        geometry: FireGeometry,
    ) {
        val slot = flames.firstOrNull { !it.active } ?: return
        val spreadHalf = geometry.baseWidth * 0.5f
        val spreadSample = randomInRange(random, -1f, 1f) * randomInRange(random, 0f, spreadHalf)
        val spreadJitter = 0.6f + random.nextFloat() * 0.4f
        val x = geometry.baseX + spreadSample * spreadJitter
        val edge = (abs(x - geometry.baseX) / spreadHalf.coerceAtLeast(1f)).coerceIn(0f, 1f)

        // Biased toward/below baseY (rather than centered on it) so flames spawn at the log crossing
        // (drawLogs' logCenterY sits a few dp below baseY) instead of slightly above it.
        val ySpawn = geometry.baseY + randomInRange(random, 2f, 10f)
        val vxDriftScale = 0.004f
        val vx = (x - geometry.baseX) * vxDriftScale + randomInRange(random, -0.15f, 0.15f)
        val vySpread = randomInRange(random, 0.8f, 1.3f)
        val edgeVyDamping = 1f - edge * 0.35f
        val vy = -(spec.flameVy * spec.intensity) * vySpread * edgeVyDamping
        val lifespan = randomInRange(random, 46f, 74f) * spec.flameLifeScale
        val intensityRadiusWeight = 0.7f + spec.intensity * 0.4f
        val edgeRadiusDamping = 1f - edge * 0.3f
        val baseRadius =
            randomInRange(random, 9f, 15f) * spec.flameWidthScale * intensityRadiusWeight * edgeRadiusDamping
        val wobblePhase = randomInRange(random, 0f, (2 * PI).toFloat())
        val wobbleSpeed = randomInRange(random, 0.03f, 0.08f)

        slot.active = true
        slot.x = x
        slot.y = ySpawn
        slot.vx = vx
        slot.vy = vy
        slot.age = 0f
        slot.lifespan = lifespan
        slot.baseRadius = baseRadius
        slot.wobblePhase = wobblePhase
        slot.wobbleSpeed = wobbleSpeed
    }

    private fun spawnSpark(
        spec: StageSpec,
        geometry: FireGeometry,
    ) {
        val slot = sparks.firstOrNull { !it.active } ?: return
        val spreadHalf = geometry.baseWidth * 0.5f
        val xSpawn = geometry.baseX + randomInRange(random, -spreadHalf, spreadHalf)
        val ySpawn = geometry.baseY - randomInRange(random, 0f, 20f)
        val vx = randomInRange(random, -0.25f, 0.25f)
        val intensityVyWeight = 0.7f + spec.intensity * 0.4f
        val vy = -randomInRange(random, 0.8f, 2.1f) * intensityVyWeight
        val lifespan = randomInRange(random, 90f, 200f)
        val radius = randomInRange(random, 0.7f, 2.0f)
        val wobblePhase = randomInRange(random, 0f, (2 * PI).toFloat())
        val wobbleSpeed = randomInRange(random, 0.02f, 0.06f)
        val twinkleSpeed = randomInRange(random, 0.5f, 1.4f)

        slot.active = true
        slot.x = xSpawn
        slot.y = ySpawn
        slot.vx = vx
        slot.vy = vy
        slot.age = 0f
        slot.lifespan = lifespan
        slot.radius = radius
        slot.wobblePhase = wobblePhase
        slot.wobbleSpeed = wobbleSpeed
        slot.twinkleSpeed = twinkleSpeed
    }
}

private fun randomInRange(
    random: Random,
    from: Float,
    to: Float,
): Float = from + random.nextFloat() * (to - from)

private data class StarSeed(
    val xFraction: Float,
    val yFraction: Float,
    val radius: Float,
    val twinklePhase: Float,
    val twinkleSpeed: Float,
)

/** Precomputed, area-scaled star positions — regenerated only when the canvas is resized (e.g. rotation). */
private fun generateStarField(
    canvasSize: IntSize,
    seed: Long,
): List<StarSeed> {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return emptyList()
    val random = Random(seed)
    val area = canvasSize.width.toLong() * canvasSize.height
    val starAreaPerStar = 14_000L
    val starCount = (area / starAreaPerStar).toInt().coerceIn(0, CAMPFIRE_MAX_STARS)
    return List(starCount) {
        val xFraction = random.nextFloat()
        val yFraction = random.nextFloat() * 0.62f
        val radius = randomInRange(random, 0.4f, 1.3f)
        val twinklePhase = randomInRange(random, 0f, (2 * PI).toFloat())
        val twinkleSpeed = randomInRange(random, 0.4f, 1.4f)
        StarSeed(xFraction, yFraction, radius, twinklePhase, twinkleSpeed)
    }
}

private fun DrawScope.drawStars(
    stars: List<StarSeed>,
    reducedMotion: Boolean,
    nowMs: Float,
) {
    stars.forEach { star ->
        val heightFadeScale = 1.1f
        val heightFade = (1f - star.yFraction * heightFadeScale).coerceIn(0f, 1f)
        val alpha =
            if (reducedMotion) {
                val reducedAlpha = 0.5f
                reducedAlpha * heightFade
            } else {
                val twinkleBaseline = 0.35f
                val twinkleAmplitude = 0.4f
                val twinkleTimeScale = 0.001f
                val twinkle =
                    twinkleBaseline +
                        twinkleAmplitude *
                        (0.5f + 0.5f * sin(nowMs * twinkleTimeScale * star.twinkleSpeed + star.twinklePhase))
                (twinkle * heightFade).coerceIn(0f, 1f)
            }
        if (alpha <= 0f) return@forEach
        val center = Offset(star.xFraction * size.width, star.yFraction * size.height)
        drawCircle(color = CampfireStarColor.copy(alpha = alpha), radius = star.radius, center = center)
    }
}

private fun DrawScope.drawCoalBed(
    geometry: FireGeometry,
    coalIntensity: Float,
    gv: Float,
) {
    val coalRadius = (geometry.baseWidth * (1.1f + 0.2f * gv)).coerceAtLeast(1f)
    val center = Offset(geometry.baseX, geometry.baseY)
    val coreAlpha = (0.5f * coalIntensity * gv).coerceIn(0f, 1f)
    val midAlpha = (0.28f * coalIntensity * gv).coerceIn(0f, 1f)
    val brush =
        Brush.radialGradient(
            0f to Color(1f, 150f / 255f, 40f / 255f, coreAlpha),
            0.5f to Color(220f / 255f, 70f / 255f, 20f / 255f, midAlpha),
            1f to Color(120f / 255f, 20f / 255f, 10f / 255f, 0f),
            center = center,
            radius = coalRadius,
        )
    val ellipseScaleY = 0.5f
    scale(scaleX = 1f, scaleY = ellipseScaleY, pivot = center) {
        drawCircle(brush = brush, radius = coalRadius, center = center)
    }
}

private fun DrawScope.drawLogs(
    geometry: FireGeometry,
    gv: Float,
) {
    val logWidth = geometry.baseWidth * 1.5f
    val logHeight = (geometry.baseWidth * 0.15f).coerceAtLeast(11.dp.toPx())
    val logCenterY = geometry.baseY + 6.dp.toPx()
    val logOffsetX = geometry.baseWidth * 0.16f
    val leftLogCenter = Offset(geometry.baseX - logOffsetX, logCenterY)
    val rightLogCenter = Offset(geometry.baseX + logOffsetX, logCenterY)
    val leftAngleDegrees = -9.17f
    val rightAngleDegrees = 9.74f
    drawLog(leftLogCenter, logWidth, logHeight, leftAngleDegrees, gv)
    drawLog(rightLogCenter, logWidth, logHeight, rightAngleDegrees, gv)
}

private fun DrawScope.drawLog(
    center: Offset,
    width: Float,
    height: Float,
    angleDegrees: Float,
    gv: Float,
) {
    rotate(degrees = angleDegrees, pivot = center) {
        val topLeft = Offset(center.x - width / 2f, center.y - height / 2f)
        val brush =
            Brush.verticalGradient(
                colors = listOf(Color(0xFF3A2418), Color(0xFF24160F), Color(0xFF160D09)),
                startY = topLeft.y,
                endY = topLeft.y + height,
            )
        val cornerRadius = CornerRadius(height / 2f)
        drawRoundRect(brush = brush, topLeft = topLeft, size = Size(width, height), cornerRadius = cornerRadius)

        // Charred, glowing end — brightens with the fire's breathing glow.
        val emberCenter = Offset(center.x - width / 2f + height * 0.4f, center.y)
        val emberAlpha = (0.5f * (0.6f + 0.4f * gv)).coerceIn(0f, 1f)
        val emberTopLeft = Offset(emberCenter.x - height * 0.28f, emberCenter.y - height * 0.34f)
        val emberSize = Size(height * 0.56f, height * 0.68f)
        val emberColor = Color(0xFFFF7828).copy(alpha = emberAlpha)
        drawOval(color = emberColor, topLeft = emberTopLeft, size = emberSize)
    }
}

private fun DrawScope.drawFlame(flame: FlameParticle) {
    val t = (flame.age / flame.lifespan).coerceIn(0f, 1f)
    val alpha = flameAlphaEnvelope(t)
    if (alpha <= 0f) return
    val radius = flame.baseRadius * flameRadiusScale(t)
    if (radius <= 0f) return
    val center = Offset(flame.x, flame.y)
    val midAgeOffset = 0.2f
    val midAlphaScale = 0.5f
    val core = flameLickColor(t, alpha)
    val mid = flameLickColor((t + midAgeOffset).coerceAtMost(1f), alpha * midAlphaScale)
    val gradientMidStop = 0.6f
    val brush =
        Brush.radialGradient(
            0f to Color(core.red, core.green, core.blue, core.alpha),
            gradientMidStop to Color(mid.red, mid.green, mid.blue, mid.alpha),
            1f to Color(60f / 255f, 10f / 255f, 5f / 255f, 0f),
            center = center,
            radius = radius,
        )
    drawCircle(brush = brush, radius = radius, center = center, blendMode = BlendMode.Plus)
}

private fun DrawScope.drawSpark(spark: SparkParticle) {
    val t = (spark.age / spark.lifespan).coerceIn(0f, 1f)
    val twinkleBaseline = 0.55f
    val twinkleAmplitude = 0.45f
    val twinkleTimeScale = 0.3f
    val twinkle = twinkleBaseline + twinkleAmplitude * sin(spark.age * spark.twinkleSpeed * twinkleTimeScale)
    val alphaScale = 0.9f
    val alpha = ((1f - t) * twinkle * alphaScale).coerceIn(0f, 1f)
    if (alpha <= 0f) return
    val radiusAgeDamping = 0.4f
    val radiusBloomScale = 3.2f
    val radius = (spark.radius * (1f - t * radiusAgeDamping) * radiusBloomScale).coerceAtLeast(0f)
    if (radius <= 0f) return
    val center = Offset(spark.x, spark.y)
    val color = emberSparkColor(t, alpha)
    val brush =
        Brush.radialGradient(
            0f to Color(color.red, color.green, color.blue, color.alpha),
            1f to Color(1f, 90f / 255f, 20f / 255f, 0f),
            center = center,
            radius = radius,
        )
    drawCircle(brush = brush, radius = radius, center = center, blendMode = BlendMode.Plus)
}

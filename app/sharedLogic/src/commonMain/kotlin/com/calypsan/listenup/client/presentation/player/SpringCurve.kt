package com.calypsan.listenup.client.presentation.player

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Samples a damped spring from 0 to 1, so web can play the same spring Android's Compose animator plays. Web has no
 * spring animator: its morph runs on a CSS `linear()` easing built from these samples (canvas `Spec`, "web: `linear()`
 * sampled from default spatial, ≈ 500 ms"). Android never calls this; it uses Compose's own spring with the same
 * [SpringParameters].
 *
 * The spring has unit mass. It is settled once it stays within [SETTLE_DISPLACEMENT] of 1 and moves slower than
 * [SETTLE_VELOCITY] progress per second for good.
 */
object SpringCurve {
    /** How close to 1 counts as arrived. */
    const val SETTLE_DISPLACEMENT: Float = 0.001f

    /** How slow (progress per second) counts as still. */
    const val SETTLE_VELOCITY: Float = 0.01f

    private const val MAX_MILLIS = 10_000

    /**
     * How long [spring] takes to settle from 0 at rest, or launched at [initialVelocity] progress per second (a
     * finger's release carried into it), in whole milliseconds. Pure.
     */
    fun settleMillis(
        spring: SpringParameters,
        initialVelocity: Float = 0f,
    ): Int {
        val motion = SpringMotion(spring, initialVelocity)
        var lastUnsettled = 0
        for (ms in 0..MAX_MILLIS) {
            val t = ms / MILLIS_PER_SECOND
            if (abs(motion.position(t) - 1.0) >= SETTLE_DISPLACEMENT || abs(motion.velocity(t)) >= SETTLE_VELOCITY) {
                lastUnsettled = ms
            }
        }
        return lastUnsettled + 1
    }

    /**
     * The spring's position every [stepMs] from 0 until it settles, launched at [initialVelocity] progress per second.
     * The first sample is 0 and the last is exactly 1, so a `linear()` easing built from them starts and ends where the
     * motion does. Pure.
     */
    fun sample(
        spring: SpringParameters,
        initialVelocity: Float,
        stepMs: Int,
    ): List<Float> {
        require(stepMs > 0) { "stepMs must be positive" }
        val motion = SpringMotion(spring, initialVelocity)
        val total = settleMillis(spring, initialVelocity)
        val steps = (total + stepMs - 1) / stepMs
        return List(steps + 1) { index ->
            if (index == steps) 1f else motion.position(index * stepMs / MILLIS_PER_SECOND).toFloat()
        }
    }

    private const val MILLIS_PER_SECOND = 1000.0
}

/** The closed-form solution of a unit-mass spring pulled from 0 towards 1, under-, critically or over-damped. */
private class SpringMotion(
    spring: SpringParameters,
    initialVelocity: Float,
) {
    private val omega = sqrt(spring.stiffness.toDouble())
    private val zeta = spring.dampingRatio.toDouble()
    private val v0 = initialVelocity.toDouble()

    // The offset from the target, y = x − 1, starts at −1.
    private val start = -1.0

    fun position(t: Double): Double = 1.0 + offset(t)

    fun velocity(t: Double): Double {
        val dt = 1e-6
        return (offset(t + dt) - offset(t)) / dt
    }

    private fun offset(t: Double): Double =
        when {
            zeta < 1.0 -> {
                val damped = omega * sqrt(1.0 - zeta * zeta)
                val sinPart = (v0 + zeta * omega * start) / damped
                exp(-zeta * omega * t) * (start * cos(damped * t) + sinPart * sin(damped * t))
            }
            zeta == 1.0 -> {
                exp(-omega * t) * (start + (v0 + omega * start) * t)
            }
            else -> {
                val root = sqrt(zeta * zeta - 1.0)
                val r1 = -omega * (zeta - root)
                val r2 = -omega * (zeta + root)
                val c2 = (v0 - r1 * start) / (r2 - r1)
                val c1 = start - c2
                c1 * exp(r1 * t) + c2 * exp(r2 * t)
            }
        }
}

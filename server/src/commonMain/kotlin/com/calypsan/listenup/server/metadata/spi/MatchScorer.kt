package com.calypsan.listenup.server.metadata.spi

import kotlin.math.abs
import kotlin.math.max

/**
 * The phase-1 match scorer — a pure function that rates how confidently a catalog
 * [BookMatch] is the same book as the local [BookIdentity] being enriched.
 *
 * Implements the approved weighting `0.7·duration + 0.2·title + 0.1·author`:
 * runtime dominates because two editions of the same title diverge most reliably by
 * length, title is the next-strongest signal, and author breaks near-ties. Every
 * component is normalized to `0.0..1.0`, so the blended score is too.
 *
 * **Graceful degradation.** A signal contributes only when both sides carry it — a
 * freshly scanned book may know its runtime but not its author, and a keyless catalog
 * hit may omit runtime. The active weights are renormalized over what's present, so a
 * duration-only comparison still spans the full `0.0..1.0` range rather than being
 * capped at `0.7`. When nothing is comparable the score is `0.0` — the candidate can't
 * be ranked, so it sinks.
 *
 * Pure and I/O-free, so it is exhaustively unit- and property-testable without a
 * provider or coordinator.
 */
internal object MatchScorer {
    /** Weight of the runtime-proximity signal — the strongest, per the approved model. */
    private const val DURATION_WEIGHT: Double = 0.7

    /** Weight of the title-similarity signal. */
    private const val TITLE_WEIGHT: Double = 0.2

    /** Weight of the author-similarity signal. */
    private const val AUTHOR_WEIGHT: Double = 0.1

    /**
     * Relative runtime difference at which duration proximity reaches `0.0`. A 50%
     * length gap is a different edition (or a different book) with high confidence;
     * proximity falls linearly from `1.0` at an exact match to `0.0` here.
     */
    private const val DURATION_TOLERANCE: Double = 0.5

    /**
     * Scores [candidate] against the [local] book in `0.0..1.0`. Higher is a better
     * match. See the class KDoc for the weighting and degradation rules.
     */
    fun score(
        local: BookIdentity,
        candidate: BookMatch,
    ): Double {
        var weightSum = 0.0
        var weighted = 0.0

        val localTitle = local.title.tokenize()
        val candidateTitle = candidate.title.tokenize()
        if (localTitle.isNotEmpty() && candidateTitle.isNotEmpty()) {
            weighted += TITLE_WEIGHT * diceCoefficient(localTitle, candidateTitle)
            weightSum += TITLE_WEIGHT
        }

        val localDuration = local.durationMs
        val candidateDuration = candidate.durationMs
        if (localDuration != null && localDuration > 0 && candidateDuration != null && candidateDuration > 0) {
            weighted += DURATION_WEIGHT * durationProximity(localDuration, candidateDuration)
            weightSum += DURATION_WEIGHT
        }

        val localAuthor = local.primaryAuthor?.tokenize().orEmpty()
        val candidateAuthor = candidate.author?.tokenize().orEmpty()
        if (localAuthor.isNotEmpty() && candidateAuthor.isNotEmpty()) {
            weighted += AUTHOR_WEIGHT * diceCoefficient(localAuthor, candidateAuthor)
            weightSum += AUTHOR_WEIGHT
        }

        return if (weightSum == 0.0) 0.0 else (weighted / weightSum).coerceIn(0.0, 1.0)
    }

    /**
     * Re-scores every [candidate][candidates] against [local] and returns them
     * best-first. The sort is stable, so equally scored candidates keep the source's
     * own relevance order (e.g. Audible's ranking).
     */
    fun rank(
        local: BookIdentity,
        candidates: List<BookMatch>,
    ): List<BookMatch> =
        candidates
            .map { it.copy(score = score(local, it)) }
            .sortedByDescending { it.score }

    /**
     * Runtime closeness as `1 - relativeDifference / tolerance`, clamped to `0.0..1.0`.
     * Relative (not absolute) so a five-minute gap is negligible on a ten-hour book but
     * decisive on a ten-minute one.
     */
    private fun durationProximity(
        a: Long,
        b: Long,
    ): Double {
        val relativeDifference = abs(a - b).toDouble() / max(a, b)
        return (1.0 - relativeDifference / DURATION_TOLERANCE).coerceIn(0.0, 1.0)
    }

    /**
     * Sørensen–Dice coefficient over token sets: `2·|A∩B| / (|A|+|B|)`. Token-set based,
     * so it is order-independent ("Sanderson, Brandon" == "Brandon Sanderson") and
     * tolerant of subtitle noise (extra tokens dilute rather than break the match).
     * Both sets are non-empty at every call site.
     */
    private fun diceCoefficient(
        a: Set<String>,
        b: Set<String>,
    ): Double {
        val intersection = a.count { it in b }
        return 2.0 * intersection / (a.size + b.size)
    }

    /**
     * Lower-cases, splits on any non-alphanumeric run, and dedupes into a token set.
     * Punctuation, casing, and separator differences ("The Way of Kings" vs
     * "the-way-of-kings") collapse to the same tokens.
     */
    private fun String.tokenize(): Set<String> =
        buildString { this@tokenize.forEach { append(if (it.isLetterOrDigit()) it.lowercaseChar() else ' ') } }
            .split(' ')
            .filterTo(mutableSetOf()) { it.isNotEmpty() }
}

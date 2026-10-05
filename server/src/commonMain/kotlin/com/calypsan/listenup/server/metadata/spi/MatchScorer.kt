package com.calypsan.listenup.server.metadata.spi

import kotlin.math.abs
import kotlin.math.max

/**
 * The phase-1 match scorer — a pure function that rates how confidently a catalog
 * [BookMatch] is the same book as the local [BookIdentity] being enriched.
 *
 * Implements the weighting `0.55·duration + 0.2·title + 0.1·author + 0.15·narrator`
 * (the matching redesign): runtime dominates because two editions of the same title
 * diverge most reliably by length, title is the next-strongest signal, the narrators
 * tell a dramatisation or another reading apart, and author breaks near-ties. Every
 * component is normalized to `0.0..1.0`, so the blended score is too.
 *
 * **Graceful degradation.** A signal contributes only when both sides carry it — a
 * freshly scanned book may know its runtime but not its author, and a keyless catalog
 * hit may omit runtime. The active weights are renormalized over what's present, so a
 * duration-only comparison still spans the full `0.0..1.0` range rather than being
 * capped at `0.55`. When nothing is comparable the score is `0.0` — the candidate can't
 * be ranked, so it sinks.
 *
 * Pure and I/O-free, so it is exhaustively unit- and property-testable without a
 * provider or coordinator.
 */
internal object MatchScorer {
    /** Weight of the runtime-proximity signal — the strongest, per the approved model. */
    private const val DURATION_WEIGHT: Double = 0.55

    /** Weight of the title-similarity signal. */
    private const val TITLE_WEIGHT: Double = 0.2

    /** Weight of the author-similarity signal. */
    private const val AUTHOR_WEIGHT: Double = 0.1

    /** Weight of the narrator-set signal: the same title read by someone else is another edition. */
    private const val NARRATOR_WEIGHT: Double = 0.15

    /**
     * Relative runtime difference at which duration proximity reaches `0.0`. A 50%
     * length gap is a different edition (or a different book) with high confidence;
     * proximity falls linearly from `1.0` at an exact match to `0.0` here.
     */
    private const val DURATION_TOLERANCE: Double = 0.5

    /** Separates a main title from its subtitle ("Project Hail Mary: A Novel"). */
    private const val SUBTITLE_DELIMITER: Char = ':'

    /**
     * The title-and-author score at or above which a rating-catalog hit is accepted as
     * the same book. A wrong score is worse than no score, so this errs strict.
     *
     * Title and author are renormalised to `2/3` and `1/3`. A right author with a
     * one-word-different title ("The Pursuit of God" vs "The Pursuit of Happiness")
     * scores 0.833, so the bar sits just above it at 0.85; exact matches and
     * accent/hyphen variants of the author clear it comfortably.
     */
    const val CONFIDENT_RATING_MATCH: Double = 0.85

    /**
     * Whether [candidate] is unmistakably the same book as [local], judged on title and
     * author alone. Rating catalogs rarely carry a runtime, so duration is excluded, and
     * both an author and a title are required: a title by itself is how a study guide
     * borrows a classic's rating.
     *
     * A subtitle present on only one side ("Project Hail Mary" vs "Project Hail Mary: A
     * Novel") is forgiven; two different subtitles ("Dune: Messiah" vs "Dune: Children
     * of Dune") are not. [score] and [rank] are unaffected.
     */
    fun isConfidentRatingMatch(
        local: BookIdentity,
        candidate: BookMatch,
    ): Boolean {
        if (local.primaryAuthor == null || candidate.author == null) return false
        val titleAndAuthorOnly = local.copy(durationMs = null) to candidate.copy(durationMs = null)
        val (localSignals, candidateSignals) = titleAndAuthorOnly
        if (score(localSignals, candidateSignals) >= CONFIDENT_RATING_MATCH) return true

        val localHasSubtitle = SUBTITLE_DELIMITER in local.title
        val candidateHasSubtitle = SUBTITLE_DELIMITER in candidate.title
        if (localHasSubtitle == candidateHasSubtitle) return false
        val withoutSubtitle =
            score(
                localSignals.copy(title = local.title.substringBefore(SUBTITLE_DELIMITER)),
                candidateSignals.copy(title = candidate.title.substringBefore(SUBTITLE_DELIMITER)),
            )
        return withoutSubtitle >= CONFIDENT_RATING_MATCH
    }

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

        val localNarrators = local.narrators.toNameSet()
        val candidateNarrators = candidate.narrators.toNameSet()
        if (localNarrators.isNotEmpty() && candidateNarrators.isNotEmpty()) {
            weighted += NARRATOR_WEIGHT * diceCoefficient(localNarrators, candidateNarrators)
            weightSum += NARRATOR_WEIGHT
        }

        return if (weightSum == 0.0) 0.0 else (weighted / weightSum).coerceIn(0.0, 1.0)
    }

    /** Whether [a] and [b] are the same text once case, punctuation and word order are set aside. */
    fun sameText(
        a: String,
        b: String,
    ): Boolean = a.tokenize().let { it.isNotEmpty() && it == b.tokenize() }

    /** Whether [a] and [b] name the same people, in any order, case or "Last, First" form. */
    fun sameNames(
        a: List<String>,
        b: List<String>,
    ): Boolean = a.toNameSet().let { it.isNotEmpty() && it == b.toNameSet() }

    /** Each name as its sorted tokens, so "Porter, Ray" and "Ray Porter" are one person. */
    private fun List<String>.toNameSet(): Set<String> =
        mapNotNull { name ->
            name
                .tokenize()
                .sorted()
                .joinToString(" ")
                .takeIf { it.isNotEmpty() }
        }.toSet()

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

package com.calypsan.listenup.server.metadata.spi

/**
 * Ranks contributor search hits closest-name-first against the user's query.
 *
 * Audnexus's author search is fuzzy — a two-word query can return 25 loosely
 * related hits. This ranker re-orders them with the same token-set
 * Sørensen–Dice similarity [MatchScorer] uses for its title/author signals:
 * order-independent ("King, Stephen" == "Stephen King") and tolerant of
 * decorations ("Tim Curry - introductions"). The sort is stable, so equally
 * scored hits keep the catalog's own relevance order.
 *
 * ABS solves the same problem by auto-picking the single closest hit
 * (Levenshtein ≤ 3); we keep the picker UI — two different people can share a
 * name — so we rank instead of pick. Pure and I/O-free.
 */
internal object ContributorHitRanker {
    /** Returns [hits] sorted best-first by name similarity to [query]; input order on a blank query. */
    fun rank(
        query: String,
        hits: List<ContributorHitMeta>,
    ): List<ContributorHitMeta> {
        val queryTokens = query.tokenize()
        if (queryTokens.isEmpty()) return hits
        return hits.sortedByDescending { hit -> similarity(queryTokens, hit.name.tokenize()) }
    }

    /** Sørensen–Dice coefficient over token sets: `2·|A∩B| / (|A|+|B|)`; `0.0` when either side is empty. */
    private fun similarity(
        a: Set<String>,
        b: Set<String>,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        return 2.0 * intersection / (a.size + b.size)
    }

    /** Lower-cases, splits on any non-alphanumeric run, and dedupes into a token set. */
    private fun String.tokenize(): Set<String> =
        buildString { this@tokenize.forEach { append(if (it.isLetterOrDigit()) it.lowercaseChar() else ' ') } }
            .split(' ')
            .filterTo(mutableSetOf()) { it.isNotEmpty() }
}

package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Every web book card draws its `Cover` with an `overlay` — the slot the collection lock
 * (`RestrictedMarker`) lives in.
 *
 * ⛔ **The regression this exists to stop.** The lock is opt-in per call site: a new card that
 * calls `Cover(…)` without `overlay =` compiles, renders, and silently tells an admin a restricted
 * book is public. The 2026-10 collection-visibility work threaded the lock through every card by
 * hand; this keeps the next card from forgetting it.
 *
 * Every `Cover(` call under `:app:webApp`'s `web/features/` must pass `overlay =`, unless its file
 * is in [BARE_COVER_EXEMPTIONS] — a surface that is not a book card, or shows only held books (which
 * never wear the lock). Each exemption names how many bare covers it may hold and why, so a book
 * card added beside an exempt hero is still caught, and a stale entry fails too.
 */
class WebBookCoversCarryTheLockRule :
    FunSpec({
        test("every web book-card Cover passes an overlay for the collection lock") {
            val files = webFeatureSources()
            assertScopeNotEmpty(
                files.keys,
                expectedMin = 100,
                why = "every :app:webApp jsMain file under web/features — if discovery breaks, this rule polices nothing",
            )
            val offenders =
                files.mapNotNull { (path, text) ->
                    val bare = bareCoverLines(text)
                    val allowed = BARE_COVER_EXEMPTIONS[path]?.bareCovers ?: 0
                    when {
                        bare.size > allowed ->
                            "$path:${bare.joinToString(",")} — a Cover without overlay = { RestrictedMarker(…) }"
                        bare.size < allowed ->
                            "$path — exempts $allowed bare Cover(s) but has ${bare.size}; tighten BARE_COVER_EXEMPTIONS"
                        else -> null
                    }
                } + (BARE_COVER_EXEMPTIONS.keys - files.keys).map { "$it — exempted but no longer exists" }
            withClue(offenders.joinToString("\n", prefix = "\n")) { offenders.shouldBeEmpty() }
        }
    })

/** How many overlay-less covers a file may hold, and the one-line reason it is not a book card. */
internal data class BareCoverExemption(
    val bareCovers: Int,
    val reason: String,
)

/** Keyed by path relative to `com/calypsan/listenup/web/`. */
internal val BARE_COVER_EXEMPTIONS: Map<String, BareCoverExemption> =
    mapOf(
        "features/admin/AdminInboxPage.kt" to BareCoverExemption(1, "the inbox lists held books only, which never wear the lock"),
        "features/library/LibraryInboxStrip.kt" to BareCoverExemption(1, "an aria-hidden fan of held books in the inbox strip"),
        "features/bookdetail/BookDetailPage.kt" to BareCoverExemption(1, "the hero cover; the page's Visibility panel says it in words"),
        "features/bookedit/CoverField.kt" to BareCoverExemption(1, "the editor's cover picker, not a card"),
        "features/hardcover/HardcoverPage.kt" to BareCoverExemption(1, "a Hardcover matching-task row, not a browse card"),
        "features/nowplaying/NowPlayingPanel.kt" to BareCoverExemption(1, "the player's art, for the book you are already playing"),
        "features/seriesdetail/SeriesDetailPage.kt" to BareCoverExemption(1, "the series hero; its book rows carry the lock"),
        "features/serieslist/SeriesListPage.kt" to BareCoverExemption(1, "a series card — the first book's art stands for the series"),
    )

private fun webFeatureSources(): Map<String, String> =
    productionScope()
        .files
        .filter { "/app/webApp/src/jsMain/" in it.path && "/com/calypsan/listenup/web/features/" in it.path }
        .associate { it.path.substringAfter("/com/calypsan/listenup/web/") to it.text }

private val COVER_CALL = Regex("""(?<!\w)Cover\(""")

private val OVERLAY_ARG = Regex("""\boverlay\s*=""")

/**
 * 1-based lines of [source] where a `Cover(` call begins whose argument list never names `overlay =`.
 * Comment lines and the declaration `fun Cover(` are skipped; the argument list runs to the
 * matching close paren, so a multi-line call is read whole.
 */
internal fun bareCoverLines(source: String): List<Int> {
    val lineStarts = source.lines().runningFold(0) { offset, line -> offset + line.length + 1 }
    return COVER_CALL
        .findAll(source)
        .filterNot { match -> isCommentOrDeclaration(source, match.range.first) }
        .filterNot { match -> OVERLAY_ARG.containsMatchIn(argumentList(source, match.range.last)) }
        .map { match -> lineStarts.indexOfLast { it <= match.range.first } + 1 }
        .toList()
}

private fun isCommentOrDeclaration(
    source: String,
    at: Int,
): Boolean {
    val lineStart = source.lastIndexOf('\n', at - 1) + 1
    val before = source.substring(lineStart, at)
    val code = before.trimStart()
    return code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || before.trimEnd().endsWith("fun")
}

/** The text between the `(` at [open] and its matching `)`. */
private fun argumentList(
    source: String,
    open: Int,
): String {
    var depth = 0
    for (i in open until source.length) {
        when (source[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return source.substring(open + 1, i)
        }
    }
    return source.substring(open + 1)
}

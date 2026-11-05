package com.calypsan.listenup.client.presentation.chaptereditor

import com.calypsan.listenup.client.domain.model.Chapter

/** The two levels a marker can open. A chapter may open either, both, or neither. */
enum class ChapterMarkerTier {
    /** Opens a Part. */
    Part,

    /** Opens a Book, in an omnibus. */
    Book,

    ;

    /** The name of the marker [chapter] carries at this tier, or null when it opens none. */
    fun titleOn(chapter: Chapter): String? =
        when (this) {
            Part -> chapter.partTitle
            Book -> chapter.bookTitle
        }
}

/**
 * An offer to turn chapters that look like parts into Part markers.
 *
 * Only ever an offer: nothing changes until the reader accepts it.
 *
 * @property titles the matching chapters' titles in book order. Their count is what the offer says
 *   it would mark, and there are always at least two.
 */
data class PartSuggestion(
    val titles: List<String>,
)

private const val UNITS = "one|two|three|four|five|six|seven|eight|nine"
private const val TEENS = "ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen"
private const val TENS = "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"

/**
 * I to XXXIX. Parts run to a handful, and a wider alphabet makes "Part C" read as 100 rather than as
 * the third of A, B, C.
 *
 * ⛔ The lookahead keeps it from matching nothing. Kotlin/JS `Regex.matches` takes the first
 * alternative that matches and only then checks the end, so an empty match here would end the
 * attempt before the number words were ever tried, in the browser alone.
 */
private const val ROMAN = "(?=[ivx])x{0,3}(?:ix|iv|v?i{0,3})"

/** `Part <n>`, where n is digits, a Roman numeral to XXXIX or an English number word, and nothing more. */
private val PART_TITLE =
    Regex("""part\s+(?:\d+|$ROMAN|(?:$TENS)(?:[- ](?:$UNITS))?|$TEENS|$UNITS)""", RegexOption.IGNORE_CASE)

private const val MIN_SUGGESTED_PARTS = 2

/** True when this title is `Part <n>` and nothing else: "Part Two: The Reckoning" names a chapter. */
internal fun String.looksLikePartTitle(): Boolean = PART_TITLE.matches(trim())

/**
 * The offer this set earns, or null.
 *
 * A book with any marker has none: its structure is the reader's now, and second-guessing it would
 * be noise. One match has none either, because one is a coincidence rather than a structure.
 */
fun List<Chapter>.partSuggestion(): PartSuggestion? {
    if (any { it.partTitle != null || it.bookTitle != null }) return null
    val titles = map { it.title.trim() }.filter { it.looksLikePartTitle() }
    return if (titles.size < MIN_SUGGESTED_PARTS) null else PartSuggestion(titles)
}

/** Marks every chapter [partSuggestion] named, each after its own title. One edit, so one undo. */
internal fun List<Chapter>.withSuggestedParts(): List<Chapter> {
    if (partSuggestion() == null) return this
    return map { chapter ->
        val title = chapter.title.trim()
        if (title.looksLikePartTitle()) chapter.copy(partTitle = title) else chapter
    }
}

/**
 * What a new marker on [chapterId] is called until the reader says otherwise.
 *
 * A new part takes the chapter's own title when that already reads as one. Otherwise it is
 * [tierWord] and the next number after the tier's existing markers, skipping any already in use.
 * A book never borrows the title: a chapter called "Part III" is no name for a book.
 *
 * @param tierWord the tier's name in the reader's language, which is why the caller supplies it.
 */
internal fun List<Chapter>.defaultMarkerTitle(
    chapterId: String,
    tier: ChapterMarkerTier,
    tierWord: String,
): String {
    val chapterTitle = firstOrNull { it.id == chapterId }?.run { title.trim() }
    if (tier == ChapterMarkerTier.Part && chapterTitle?.looksLikePartTitle() == true) return chapterTitle

    val taken = mapNotNullTo(mutableSetOf()) { tier.titleOn(it)?.lowercase() }
    val number = generateSequence(taken.size + 1) { it + 1 }.first { "$tierWord $it".lowercase() !in taken }
    return "$tierWord $number"
}

/** The chapters a [tier] marker can move to: a chapter already opening that tier is not one. */
fun List<Chapter>.moveTargets(tier: ChapterMarkerTier): List<Chapter> = filter { tier.titleOn(it) == null }

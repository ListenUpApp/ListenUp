package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.readers.BookReaders

/** How many avatars the social line draws. */
private const val SOCIAL_AVATARS = 3

/** How many names one clause of the social line spells out before "and N others". */
private const val NAMED_PER_CLAUSE = 2

/**
 * Who else is reading a book, for the hero's social line ("Maya and Priya are listening · Tom finished it"). Never
 * includes you. Carries no copy: each platform builds the sentence from its own string catalog, with plurals.
 *
 * @property listening Others listening now, in the repository's order.
 * @property finished Others who have finished it and are not listening again.
 * @property avatars Up to three of [listening] then [finished], for the overlapping circles.
 * @property othersCount How many readers the sentence leaves unnamed: everyone past the first two of each clause.
 */
data class ReadersSummary(
    val listening: List<ReaderRef>,
    val finished: List<ReaderRef>,
    val avatars: List<ReaderRef>,
    val othersCount: Int,
)

/**
 * A reader named on the social line.
 *
 * @property userId Their user id: the avatar's seed and the profile link.
 * @property displayName Their full name: the avatar's initials.
 */
data class ReaderRef(
    val userId: String,
    val displayName: String,
) {
    /** The name the sentence uses: the first word of [displayName], as the boards write it ("Maya and Priya"). */
    val firstName: String
        get() = displayName.trim().substringBefore(' ')
}

/** The social-line summary of these readers. Pure. */
internal fun BookReaders.summary(): ReadersSummary {
    val others = readers.filterNot { it.isYou }
    val listening = others.filter { it.currentProgressPct != null }.map { ReaderRef(it.userId, it.displayName) }
    val finished =
        others
            .filter { it.currentProgressPct == null && it.finishes.isNotEmpty() }
            .map { ReaderRef(it.userId, it.displayName) }
    return ReadersSummary(
        listening = listening,
        finished = finished,
        avatars = (listening + finished).take(SOCIAL_AVATARS),
        othersCount =
            (listening.size - NAMED_PER_CLAUSE).coerceAtLeast(0) + (finished.size - NAMED_PER_CLAUSE).coerceAtLeast(0),
    )
}

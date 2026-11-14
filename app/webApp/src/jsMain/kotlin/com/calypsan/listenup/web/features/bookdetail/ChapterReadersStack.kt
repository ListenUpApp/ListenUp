package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaders
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReadership
import com.calypsan.listenup.client.presentation.bookdetail.ReaderClause
import com.calypsan.listenup.client.presentation.bookdetail.ReaderClauseForm
import com.calypsan.listenup.client.presentation.bookdetail.ReadersPlace
import com.calypsan.listenup.web.design.UserAvatar
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Which readers each place in the chapter list carries: the shared [ChapterReadership]'s three questions.
 *
 * A seam rather than the shared type itself because [ChapterReadership] can only be built from a book's readers and
 * chapters inside sharedLogic; a spec draws the list from readers it names directly.
 */
internal interface ChapterReadersLookup {
    /** The readers on [chapterId]'s row, or on the part header standing in for that folded chapter. */
    fun onChapter(chapterId: String): ChapterReaders?

    /** The readers anywhere in the collapsed part whose header is [partId]. */
    fun inPart(partId: String): ChapterReaders?

    /** The readers on chapters outside [shownChapterIds], for the collapsed list's "Show all" button. */
    fun beyond(shownChapterIds: List<String>): ChapterReaders?

    /** Holds [None]. */
    companion object {
        /** Nobody anywhere: the readers have not loaded, or there are none. */
        val None: ChapterReadersLookup = ChapterReadership.None.asLookup()
    }
}

/** The shared projection, as the chapter list asks it. */
internal fun ChapterReadership.asLookup(): ChapterReadersLookup =
    object : ChapterReadersLookup {
        override fun onChapter(chapterId: String) = this@asLookup.onChapter(chapterId)

        override fun inPart(partId: String) = this@asLookup.inPart(partId)

        override fun beyond(shownChapterIds: List<String>) = this@asLookup.beyond(shownChapterIds)
    }

/**
 * The avatars one place carries: up to three faces, or two and a "+N" disc, overlapping. Decoration only — hidden from assistive tech, never a target — with every name on hover;
 * [ChapterReadersSentence] says the same in words. A face listening now has the coral ring and a breathing glow.
 */
@Composable
internal fun ChapterReadersStack(readers: ChapterReaders) {
    Span(attrs = {
        classes("bd-chr")
        attr("aria-hidden", "true")
        attr("title", chapterReadersTitle(readers))
    }) {
        val circles = readers.faces.size + if (readers.overflowCount > 0) 1 else 0
        readers.faces.forEachIndexed { index, face ->
            Span(attrs = {
                classes("bd-chr-f")
                if (face.isLive) classes("is-live")
                // Each circle sits over the next, the first on top.
                style { property("z-index", (circles - index).toString()) }
            }) {
                if (face.isLive) LiveGlow()
                UserAvatar(userId = face.userId, name = face.displayName, size = FACE_SIZE)
            }
        }
        if (readers.overflowCount > 0) {
            Span(attrs = {
                classes("bd-chr-f", "bd-chr-more")
                style { property("z-index", "1") }
            }) { Text("+${readers.overflowCount}") }
        }
    }
}

/** The one sentence a row's button is described by, out of sight: who is listening now, then who else is here. */
@Composable
internal fun ChapterReadersSentence(
    readers: ChapterReaders,
    sentenceId: String,
) {
    Span(attrs = {
        id(sentenceId)
        classes("sr-only")
    }) { Text(chapterReadersSentence(readers)) }
}

/**
 * The breathing glow behind a face listening now. Every glow on the page breathes together: each starts its cycle
 * as far in as the page's clock already is, so one that appears later falls into step instead of beating against
 * the others.
 */
@Composable
private fun LiveGlow() {
    val phaseMs = remember { window.performance.now() % GLOW_CYCLE_MS }
    Span(attrs = {
        classes("bd-chr-glow")
        style { property("animation-delay", "-${phaseMs.toInt()}ms") }
    })
}

/** Every name in the stack, the people listening now marked: "Maya (listening now), Priya and Tom". */
internal fun chapterReadersTitle(readers: ChapterReaders): String =
    readers.readers
        .map { if (it.isLive) "${it.displayName} (listening now)" else it.displayName }
        .let { names ->
            if (names.size < 2) names.joinToString() else "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
        }

/**
 * "Maya is listening now. Priya, Tom and 2 others are on this chapter." The English text of en.json's
 * `book.detail_chapter_readers_*`, the words Android and iOS say.
 */
internal fun chapterReadersSentence(readers: ChapterReaders): String =
    listOfNotNull(
        readers.listeningNow?.let { clause(it, are = "listening now.", isPhrase = "is listening now.") },
        readers.notListening?.let { clause(it, are = readers.place.arePhrase, isPhrase = readers.place.isPhrase) },
    ).joinToString(" ")

private fun clause(
    clause: ReaderClause,
    are: String,
    isPhrase: String,
): String {
    val first = clause.named.first().firstName
    val second = clause.named.getOrNull(1)?.firstName
    return when (clause.form) {
        ReaderClauseForm.One -> "$first $isPhrase"
        ReaderClauseForm.Two -> "$first and $second are $are"
        ReaderClauseForm.TwoAndOneOther -> "$first, $second and 1 other are $are"
        ReaderClauseForm.TwoAndOthers -> "$first, $second and ${clause.othersCount} others are $are"
    }
}

/** What one reader here is doing, by where the stack sits: "is on this chapter." */
private val ReadersPlace.isPhrase: String
    get() =
        when (this) {
            ReadersPlace.ThisChapter -> "is on this chapter."
            ReadersPlace.ThisPart -> "is in this part."
            ReadersPlace.FurtherOn -> "is on a chapter further on."
            ReadersPlace.Earlier -> "is on an earlier chapter."
            ReadersPlace.OtherChapters -> "is on another chapter."
        }

/** What several readers here are doing, after "are": "on this chapter." */
private val ReadersPlace.arePhrase: String
    get() =
        when (this) {
            ReadersPlace.ThisChapter -> "on this chapter."
            ReadersPlace.ThisPart -> "in this part."
            ReadersPlace.FurtherOn -> "on chapters further on."
            ReadersPlace.Earlier -> "on earlier chapters."
            ReadersPlace.OtherChapters -> "on other chapters."
        }

/** The canvas's avatar size on a chapter row: small enough to leave the row's height alone. */
private const val FACE_SIZE = 24

/** One breath of the live glow, as `bd-chr-breathe` in 06-shell.css runs it. */
private const val GLOW_CYCLE_MS = 2000.0

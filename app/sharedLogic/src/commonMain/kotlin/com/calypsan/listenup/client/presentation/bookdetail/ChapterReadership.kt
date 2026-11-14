package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow
import com.calypsan.listenup.client.domain.chapter.toChapterOutline
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.playback.chapterWindowAt

/** The most circles a stack draws: up to this many readers all show their faces. */
private const val MAX_CIRCLES = 3

/** The faces drawn beside the "+N" disc once a stack has more readers than [MAX_CIRCLES]. */
private const val FACES_BESIDE_OVERFLOW = 2

/** How many names one clause spells out before "and N others", as the hero's social line does. */
private const val NAMED_PER_CLAUSE = 2

private const val PERCENT = 100

/**
 * Where everyone else is in a book, for Book Detail's chapter list: which chapter row, part header and "All N chapters"
 * button carries whose avatar. Never includes you, anyone who has finished (they are in no chapter), or anyone who has
 * not started. Carries no copy: each platform builds its sentences from its own string catalog.
 *
 * Chapters are named by [Chapter.id], the id every chapter row ([ChapterUiModel.id],
 * [ChapterOutlineRow.ChapterRow]) and part header ([ChapterOutlineRow.PartHeader.chapterId]) already carries.
 */
@ConsistentCopyVisibility
data class ChapterReadership internal constructor(
    private val placed: List<PlacedReader>,
    private val chapterIndexById: Map<String, Int>,
    private val partIdByChapterId: Map<String, String>,
) {
    /** The readers on [chapterId]'s row; null when nobody is on it. An open part's header uses this for its opener. */
    fun onChapter(chapterId: String): ChapterReaders? = stackOf(ReadersPlace.ThisChapter) { it.chapterId == chapterId }

    /**
     * The readers anywhere in the part whose header is [partId] ([ChapterOutlineRow.PartHeader.chapterId]), for the
     * header of a collapsed part; null when nobody is in it. Opening the part moves each onto their own row.
     */
    fun inPart(partId: String): ChapterReaders? =
        stackOf(ReadersPlace.ThisPart) { reader -> partIdByChapterId[reader.chapterId] == partId }

    /**
     * The readers on chapters whose rows are not among [shownChapterIds], for the collapsed list's "All N chapters"
     * button; null when every reader's row is shown, as it is once the list is expanded. Its [ChapterReaders.place]
     * says whether they are all further on, all earlier, or on both sides.
     */
    fun beyond(shownChapterIds: List<String>): ChapterReaders? {
        val shown = shownChapterIds.mapNotNullTo(mutableSetOf()) { chapterIndexById[it] }
        val outside = placed.filter { it.chapterIndex !in shown }
        if (outside.isEmpty()) return null
        val lastShown = shown.maxOrNull()
        val firstShown = shown.minOrNull()
        val place =
            when {
                lastShown != null && outside.all { it.chapterIndex > lastShown } -> ReadersPlace.FurtherOn
                firstShown != null && outside.all { it.chapterIndex < firstShown } -> ReadersPlace.Earlier
                else -> ReadersPlace.OtherChapters
            }
        return ChapterReaders(place, outside.map { it.face })
    }

    private inline fun stackOf(
        place: ReadersPlace,
        predicate: (PlacedReader) -> Boolean,
    ): ChapterReaders? =
        placed
            .filter(predicate)
            .takeIf {
                it.isNotEmpty()
            }?.let { ChapterReaders(place, it.map(PlacedReader::face)) }

    /** Holds [None]. */
    companion object {
        /** Nobody on any chapter. */
        val None: ChapterReadership = ChapterReadership(emptyList(), emptyMap(), emptyMap())
    }
}

/** One reader on one chapter, and when they were last there, which orders them. */
internal data class PlacedReader(
    val face: ChapterReaderFace,
    val chapterId: String,
    val chapterIndex: Int,
    val lastPlayedAtMs: Long?,
)

/**
 * The avatars one place carries, and what a screen reader says about them.
 *
 * @property place Where the stack sits, which picks the wording of [notListening]'s sentence.
 * @property readers Everyone here, listening now first, then the most recently active. The web shows their names on
 *   hover.
 */
data class ChapterReaders(
    val place: ReadersPlace,
    val readers: List<ChapterReaderFace>,
) {
    /** The circles with faces: everyone up to three, else the first two beside a "+[overflowCount]" disc. */
    val faces: List<ChapterReaderFace>
        get() = if (readers.size <= MAX_CIRCLES) readers else readers.take(FACES_BESIDE_OVERFLOW)

    /** The number on the "+N" disc; 0 when there is none. */
    val overflowCount: Int
        get() = readers.size - faces.size

    /** Who here is listening right now ("Maya is listening now."); null when nobody is. */
    val listeningNow: ReaderClause?
        get() = clauseOf(readers.filter { it.isLive })

    /** Who here is not listening right now ("Priya, Tom and 2 others are on this chapter."); null when nobody. */
    val notListening: ReaderClause?
        get() = clauseOf(readers.filterNot { it.isLive })
}

/**
 * One face in a stack.
 *
 * @property userId Their user id: the avatar's seed.
 * @property displayName Their full name: the avatar's initials.
 * @property isLive Listening to this book right now: the coral ring and the breathing glow.
 */
data class ChapterReaderFace(
    val userId: String,
    val displayName: String,
    val isLive: Boolean,
)

/** Where a stack of readers sits. Each place has its own sentence for the people in it who are not listening now. */
enum class ReadersPlace {
    /** A chapter row: "Tom is on this chapter." */
    ThisChapter,

    /** A collapsed part's header: "Tom is in this part." */
    ThisPart,

    /** "All N chapters", with everyone past the rows shown: "Tom is on a chapter further on." */
    FurtherOn,

    /** "All N chapters", with everyone before the rows shown: "Tom is on an earlier chapter." */
    Earlier,

    /** "All N chapters", with readers on both sides of the rows shown: "Tom is on another chapter." */
    OtherChapters,
}

/**
 * One clause of a stack's sentence: up to two names, then how many go unnamed.
 *
 * @property named The first one or two people, by [ReaderRef.firstName].
 * @property othersCount How many more the clause counts without naming.
 */
data class ReaderClause(
    val named: List<ReaderRef>,
    val othersCount: Int,
) {
    /** Which of the four wordings this clause takes. */
    val form: ReaderClauseForm
        get() =
            when {
                named.size == 1 -> ReaderClauseForm.One
                othersCount == 0 -> ReaderClauseForm.Two
                othersCount == 1 -> ReaderClauseForm.TwoAndOneOther
                else -> ReaderClauseForm.TwoAndOthers
            }
}

/** The four wordings of a [ReaderClause], as the hero's social line has them. */
enum class ReaderClauseForm {
    /** "Maya is …" */
    One,

    /** "Maya and Tom are …" */
    Two,

    /** "Maya, Tom and 1 other are …" */
    TwoAndOneOther,

    /** "Maya, Tom and N others are …" */
    TwoAndOthers,
}

private fun clauseOf(faces: List<ChapterReaderFace>): ReaderClause? =
    faces.takeIf { it.isNotEmpty() }?.let { people ->
        ReaderClause(
            named = people.take(NAMED_PER_CLAUSE).map { face -> ReaderRef(face.userId, face.displayName) },
            othersCount = (people.size - NAMED_PER_CLAUSE).coerceAtLeast(0),
        )
    }

/**
 * Where everyone but you is in a book with these [chapters]. Pure.
 *
 * A reader's place is their exact position when the server sent one, else their percent of the book's length; the
 * chapter is the one that position falls in, a boundary belonging to the chapter that starts there. Readers listening
 * right now ([liveUserIds]) come first, then the most recently active, then those whose last play is unknown.
 */
internal fun chapterReadership(
    readers: List<Reader>,
    chapters: List<Chapter>,
    liveUserIds: Set<String>,
): ChapterReadership {
    val last = chapters.lastOrNull() ?: return ChapterReadership.None
    val bookDurationMs = last.startTime + last.duration
    val placed =
        readers
            .mapNotNull { reader ->
                val positionMs = reader.positionIn(bookDurationMs)
                if (reader.isYou || positionMs == null) return@mapNotNull null
                val index = chapterWindowAt(positionMs, chapters, bookDurationMs).index
                PlacedReader(
                    face = ChapterReaderFace(reader.userId, reader.displayName, isLive = reader.userId in liveUserIds),
                    chapterId = chapters[index].id,
                    chapterIndex = index,
                    lastPlayedAtMs = reader.lastPlayedAtMs,
                )
            }.sortedWith(
                compareByDescending<PlacedReader> { it.face.isLive }
                    .thenByDescending { it.lastPlayedAtMs != null }
                    .thenByDescending { it.lastPlayedAtMs },
            )
    if (placed.isEmpty()) return ChapterReadership.None
    return ChapterReadership(
        placed = placed,
        chapterIndexById = chapters.withIndex().associate { (index, chapter) -> chapter.id to index },
        partIdByChapterId = chapters.partIdByChapterId(),
    )
}

/** Where this reader is partway through, book-relative ms; null when they are not partway through. */
private fun Reader.positionIn(bookDurationMs: Long): Long? {
    val exact = currentPositionMs
    return when {
        currentProgressPct == null -> null
        exact != null -> exact.takeIf { it > 0 }
        else -> currentProgressPct.takeIf { it > 0 }?.let { pct -> bookDurationMs * pct / PERCENT }
    }
}

/** Each chapter's part, by the part header's [ChapterOutlineRow.PartHeader.chapterId]; the folded opener included. */
private fun List<Chapter>.partIdByChapterId(): Map<String, String> =
    toChapterOutline().fold(mutableMapOf()) { parts, row ->
        when (row) {
            is ChapterOutlineRow.PartHeader -> parts[row.chapterId] = row.chapterId
            is ChapterOutlineRow.ChapterRow -> row.partId?.let { parts[row.chapter.id] = it }
            is ChapterOutlineRow.BookHeader -> Unit
        }
        parts
    }

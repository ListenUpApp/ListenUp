package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.client.domain.chapter.currentChapterIndex
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.Reachability

/** How long a finished book keeps its card (spec §2.2 row 2). */
internal const val JUST_FINISHED_WINDOW_MS: Long = 72L * 60 * 60 * 1000

/** How many finished books the NothingInProgress candidate walk reads, newest finish first (plan call C16). */
internal const val SERIES_CANDIDATE_WALK = 20

/** How many covers a new listener's slot shows (spec §2.3). */
internal const val NEW_LISTENER_COVERS = 4

/** How many fellow finishers the card names (spec §2.3). */
internal const val ALSO_FINISHED_NAMES = 2

/** The loaded book as the slot decides on it: `isPlaying` dropped, so play/pause is never a decision point (P-R6). */
internal sealed interface LoadedKey {
    /** The player has not yet read back which book was loaded. */
    data object Restoring : LoadedKey

    /** No book is loaded. */
    data object NothingLoaded : LoadedKey

    /** [bookId] is loaded; [isFinished] says whether it has been finished. */
    data class Loaded(
        val bookId: String,
        val isFinished: Boolean,
    ) : LoadedKey
}

internal fun LoadedBook.toKey(): LoadedKey =
    when (this) {
        LoadedBook.Restoring -> LoadedKey.Restoring
        LoadedBook.NothingLoaded -> LoadedKey.NothingLoaded
        is LoadedBook.Loaded -> LoadedKey.Loaded(bookId, isFinished)
    }

/** A book this Home visit watched being loaded, and when: a play the positions may not show yet (plan call C10). */
internal data class FreshLoad(
    val bookId: String,
    val atMs: Long,
)

/** The resolved loaded book, plus the load this visit saw happen, if any. */
internal data class LoadedView(
    val key: LoadedKey,
    val freshLoad: FreshLoad?,
)

/**
 * The next [LoadedView] once [key] arrives; null while restoring. The first resolved value is a restore, never a fresh
 * load. A change to a different loaded book is a fresh load at [nowMs]; finishing the same book keeps the load it had.
 */
internal fun nextLoadedView(
    previous: LoadedView?,
    key: LoadedKey,
    nowMs: Long,
): LoadedView? =
    when (key) {
        LoadedKey.Restoring -> {
            null
        }

        LoadedKey.NothingLoaded -> {
            LoadedView(key, freshLoad = null)
        }

        is LoadedKey.Loaded -> {
            val sameBookBefore = (previous?.key as? LoadedKey.Loaded)?.bookId == key.bookId
            val freshLoad =
                when {
                    previous == null -> null
                    sameBookBefore -> previous.freshLoad
                    else -> FreshLoad(key.bookId, nowMs)
                }
            LoadedView(key, freshLoad)
        }
    }

/** The slot's kind, before its contents are read (spec §2.2). */
internal sealed interface SlotKind {
    /** The library's first sync is still arriving. */
    data object FirstSync : SlotKind

    /** A book is loaded, so the mini player owns it. */
    data object Absent : SlotKind

    /** Resume [bookId], the most recent book in progress. */
    data class Resume(
        val bookId: String,
    ) : SlotKind

    /** The just-finished card for [bookId]. */
    data class JustFinished(
        val bookId: String,
    ) : SlotKind

    /** Nothing in progress, but the reader has listened before. */
    data object NothingInProgress : SlotKind

    /** No listening history at all. */
    data object NewListener : SlotKind
}

/** Everything a slot kind is decided from. [loaded] is never [LoadedKey.Restoring]. */
internal data class SlotInputs(
    val loaded: LoadedKey,
    val continueListening: List<ContinueListeningItem>,
    val justFinishedBookId: String?,
    val hasHistory: Boolean,
    val isLibraryArriving: Boolean,
)

/** Spec §2.2, top to bottom; the first that applies wins. Pure. */
internal fun slotKindFor(inputs: SlotInputs): SlotKind {
    val first = inputs.continueListening.firstOrNull()
    val firstReady = inputs.continueListening.firstOrNull { it is ContinueListeningItem.Ready }
    val stillArriving = first == null || first is ContinueListeningItem.Loading
    return when {
        inputs.loaded == LoadedKey.NothingLoaded && inputs.isLibraryArriving && stillArriving -> SlotKind.FirstSync
        inputs.justFinishedBookId != null -> SlotKind.JustFinished(inputs.justFinishedBookId)
        inputs.loaded is LoadedKey.Loaded -> SlotKind.Absent
        firstReady != null -> SlotKind.Resume(firstReady.bookId)
        inputs.hasHistory -> SlotKind.NothingInProgress
        else -> SlotKind.NewListener
    }
}

/**
 * The book whose "just finished" card Home shows, or null (spec §2.2 row 2). It must be:
 * - finished within the window and not held;
 * - not dismissed for this finish — by a dismissal at or after it (synced) or by [dismissedHere] this visit;
 * - not moved on from — no other book played after the finish, nor [freshLoad]ed after it.
 *
 * The newest finish wins. Pure.
 */
internal fun justFinishedBookId(
    positions: Collection<PlaybackPosition>,
    heldBookIds: Set<String>,
    dismissedHere: Map<String, Long>,
    freshLoad: FreshLoad?,
    nowMs: Long,
): String? =
    positions
        .mapNotNull { position -> finishOf(position)?.let { finishedAt -> position to finishedAt } }
        .filter { (position, finishedAt) ->
            position.bookId !in heldBookIds &&
                nowMs - finishedAt <= JUST_FINISHED_WINDOW_MS &&
                (position.finishedCardDismissedAtMs ?: Long.MIN_VALUE) < finishedAt &&
                dismissedHere[position.bookId] != finishedAt &&
                !movedOn(
                    bookId = position.bookId,
                    finishedAtMs = finishedAt,
                    positions = positions,
                    freshLoad = freshLoad,
                )
        }.map { (position, finishedAt) -> position.bookId to finishedAt }
        .maxByOrNull { (_, finishedAt) -> finishedAt }
        ?.first

/** When [position]'s book was finished, or null when it is not finished. */
private fun finishOf(position: PlaybackPosition): Long? = if (position.isFinished) position.finishedAtMs else null

private fun movedOn(
    bookId: String,
    finishedAtMs: Long,
    positions: Collection<PlaybackPosition>,
    freshLoad: FreshLoad?,
): Boolean =
    positions.any { it.bookId != bookId && (it.lastPlayedAtMs ?: Long.MIN_VALUE) > finishedAtMs } ||
        (freshLoad != null && freshLoad.bookId != bookId && freshLoad.atMs > finishedAtMs)

/** When the slot may change kind (spec §2.1 rule 1): Home's own refresh/dismiss counter, and the loaded book. */
internal data class DecisionPoint(
    val runs: Int,
    val loaded: LoadedKey,
)

/**
 * The kind Home shows, and the decision point it was chosen at. [isProvisional] when it was chosen while the leading
 * Continue Listening book was still arriving, so the data it was chosen from was not yet the reader's.
 */
internal data class LatchedSlot(
    val point: DecisionPoint,
    val kind: SlotKind,
    val isProvisional: Boolean = false,
)

/** Whether the most recent book in progress is still arriving: its position has synced and its book has not. Pure. */
internal fun isStillArriving(continueListening: List<ContinueListeningItem>): Boolean =
    continueListening.firstOrNull() is ContinueListeningItem.Loading

/**
 * Spec §2.1: hold [previous] until a decision point passes. A FirstSync skeleton resolves in place (rule 4), and so
 * does a provisional decision — a first sign-in can settle on "nothing in progress" before its books land. So does the
 * welcome: any playback position ends it (Simon, 2026-10-14), and a store that is still filling can decide it before
 * the positions land. Pure.
 */
internal fun latch(
    previous: LatchedSlot?,
    point: DecisionPoint,
    fresh: SlotKind,
    isProvisional: Boolean = false,
): LatchedSlot =
    if (previous == null || previous.point != point || previous.kind.resolvesInPlace || previous.isProvisional) {
        LatchedSlot(point, fresh, isProvisional)
    } else {
        previous
    }

/** The kinds that give way as soon as their data does: the first-sync skeleton and the welcome. */
private val SlotKind.resolvesInPlace: Boolean
    get() = this == SlotKind.FirstSync || this == SlotKind.NewListener

/**
 * Everything in progress (Simon, 2026-10-27): Continue Listening with the loaded book moved to the front, so the book
 * in the mini player is never missing from the list. With nothing loaded the Resume book stays in (it is already
 * first: Continue Listening is most recent first) unless it is the only book in progress, when the hero alone says it.
 * A loaded book that Continue Listening does not hold (finished, held) is not added. Pure.
 */
internal fun everythingInProgressOf(
    continueListening: List<ContinueListeningItem>,
    loaded: LoadedKey,
    kind: SlotKind,
): List<ContinueListeningItem> {
    val resumeId = (kind as? SlotKind.Resume)?.bookId
    if (resumeId != null && continueListening.all { it.bookId == resumeId }) return emptyList()
    val loadedId = (loaded as? LoadedKey.Loaded)?.bookId ?: return continueListening
    val (front, rest) = continueListening.partition { it.bookId == loadedId }
    return front + rest
}

/** Finished books, newest finish first, at most [SERIES_CANDIDATE_WALK]. Pure. */
internal fun finishedNewestFirst(positions: Collection<PlaybackPosition>): List<String> =
    positions
        .asSequence()
        .filter { it.isFinished }
        .sortedByDescending { it.finishedAtMs ?: it.effectiveLastPlayedAtMs }
        .take(SERIES_CANDIDATE_WALK)
        .map { it.bookId }
        .toList()

/**
 * The first next-in-series, in walk order, that is neither started nor finished (spec §2.3). [nextOfFinished] is
 * aligned with [finishedNewestFirst]; held next books are already null ([ObserveNextInSeriesUseCase]). Pure.
 */
internal fun seriesCandidateOf(
    nextOfFinished: List<NextInSeries?>,
    positionsByBookId: Map<String, PlaybackPosition>,
): NextInSeries? =
    nextOfFinished.firstOrNull { next ->
        next != null && positionsByBookId[next.bookId].let { it == null || (it.positionMs == 0L && !it.isFinished) }
    }

/** Up to [ALSO_FINISHED_NAMES] names and the count of everyone else; a nameless finisher is counted, never shown. Pure. */
internal fun alsoFinishedOf(otherFinishers: List<String>): AlsoFinished {
    val names = otherFinishers.filter { it.isNotBlank() }.take(ALSO_FINISHED_NAMES)
    return AlsoFinished(names = names, othersCount = otherFinishers.size - names.size)
}

/** The chapter [positionMs] sits in, one-based, or null when there are no chapters. Pure. */
internal fun heroChapterOf(
    chapters: List<Chapter>,
    positionMs: Long,
): HeroChapter? =
    currentChapterIndex(chapters.map { it.startTime }, positionMs)?.let { index ->
        HeroChapter(number = index + 1, title = chapters[index].title)
    }

/** The greeting's band for [hour] (0–23): 5–11, 12–16, 17–20, else night. Pure. */
internal fun timeOfDayAt(hour: Int): TimeOfDay =
    when (hour) {
        in 5..11 -> TimeOfDay.Morning
        in 12..16 -> TimeOfDay.Afternoon
        in 17..20 -> TimeOfDay.Evening
        else -> TimeOfDay.Night
    }

/** The device's network first, then the server; unknown reads as online (spec §4.1). Pure. */
internal fun connectivityOf(
    isDeviceOnline: Boolean,
    reachability: Reachability,
): HomeConnectivity =
    when {
        !isDeviceOnline -> HomeConnectivity.Offline
        reachability == Reachability.Unreachable -> HomeConnectivity.ServerUnreachable
        else -> HomeConnectivity.Online
    }

/** The first word of [displayName], or null when there is none. Pure. */
internal fun firstNameOf(displayName: String?): String? {
    if (displayName.isNullOrBlank()) return null
    return displayName
        .trim()
        .split(" ")
        .firstOrNull()
        ?.takeIf { it.isNotBlank() }
}

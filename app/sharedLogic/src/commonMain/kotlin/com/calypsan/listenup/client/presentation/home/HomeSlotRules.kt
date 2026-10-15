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
    data object Restoring : LoadedKey

    data object NothingLoaded : LoadedKey

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
    data object FirstSync : SlotKind

    data object Absent : SlotKind

    data class Resume(
        val bookId: String,
    ) : SlotKind

    data class JustFinished(
        val bookId: String,
    ) : SlotKind

    data object NothingInProgress : SlotKind

    data object NewListener : SlotKind
}

/** Everything a slot kind is decided from. [loaded] is never [LoadedKey.Restoring]. */
internal data class SlotInputs(
    val loaded: LoadedKey,
    val continueListening: List<ContinueListeningItem>,
    val justFinishedBookId: String?,
    val hasHistory: Boolean,
    val isBuildingInitialLibrary: Boolean,
)

/** Spec §2.2, top to bottom; the first that applies wins. Pure. */
internal fun slotKindFor(inputs: SlotInputs): SlotKind {
    val first = inputs.continueListening.firstOrNull()
    val firstReady = inputs.continueListening.firstOrNull { it is ContinueListeningItem.Ready }
    val stillArriving = first == null || first is ContinueListeningItem.Loading
    return when {
        inputs.loaded == LoadedKey.NothingLoaded && inputs.isBuildingInitialLibrary && stillArriving -> SlotKind.FirstSync
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
        .mapNotNull { position -> position.finishedAtMs?.takeIf { position.isFinished }?.let { position to it } }
        .filter { (position, finishedAt) ->
            position.bookId !in heldBookIds &&
                nowMs - finishedAt <= JUST_FINISHED_WINDOW_MS &&
                (position.finishedCardDismissedAtMs ?: Long.MIN_VALUE) < finishedAt &&
                dismissedHere[position.bookId] != finishedAt &&
                !movedOn(position.bookId, finishedAt, positions, freshLoad)
        }.maxByOrNull { (_, finishedAt) -> finishedAt }
        ?.first
        ?.bookId

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

/** The kind Home shows, and the decision point it was chosen at. */
internal data class LatchedSlot(
    val point: DecisionPoint,
    val kind: SlotKind,
)

/** Spec §2.1: hold [previous] until a decision point passes; a FirstSync skeleton resolves in place (rule 4). Pure. */
internal fun latch(
    previous: LatchedSlot?,
    point: DecisionPoint,
    fresh: SlotKind,
): LatchedSlot =
    if (previous == null || previous.point != point || previous.kind == SlotKind.FirstSync) {
        LatchedSlot(point, fresh)
    } else {
        previous
    }

/** Continue Listening without the loaded book (F1) and without the book the slot shows (spec §4.1). Pure. */
internal fun alsoInProgressOf(
    continueListening: List<ContinueListeningItem>,
    loaded: LoadedKey,
    kind: SlotKind,
): List<ContinueListeningItem> {
    val excluded = setOfNotNull((loaded as? LoadedKey.Loaded)?.bookId, (kind as? SlotKind.Resume)?.bookId)
    return continueListening.filterNot { it.bookId in excluded }
}

/** Finished books, newest finish first, at most [SERIES_CANDIDATE_WALK]. Pure. */
internal fun finishedNewestFirst(positions: Collection<PlaybackPosition>): List<String> =
    positions
        .filter { it.isFinished }
        .sortedByDescending { it.finishedAtMs ?: it.effectiveLastPlayedAtMs }
        .take(SERIES_CANDIDATE_WALK)
        .map { it.bookId }

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
    return displayName.trim().split(" ").firstOrNull()?.takeIf { it.isNotBlank() }
}

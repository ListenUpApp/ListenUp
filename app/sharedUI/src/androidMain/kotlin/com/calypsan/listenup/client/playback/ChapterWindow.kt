package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.localization.SystemStrings
import com.calypsan.listenup.client.presentation.player.ChapterName
import com.calypsan.listenup.client.presentation.player.ChapterNaming
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * A single-chapter view of book-relative playback position.
 *
 * System playback surfaces (Android Auto now-playing, the notification, the lock screen,
 * Bluetooth/AVRCP, Wear) present elapsed/remaining time and the seek bar for the *current
 * chapter*, not the whole book — the same way a music player shows the current track, not
 * the whole album. [ChapterWindow] is that re-presentation: a chapter-relative window over
 * the book's absolute timeline.
 *
 * @property chapterIndex Index into the chapters list, or `-1` when the book has no chapters
 *   (the whole book is presented as one window in that case).
 * @property windowStartMs Book-relative start of this window, in milliseconds.
 * @property windowDurationMs Length of this window, in milliseconds.
 * @property positionInWindowMs Position within the window, clamped to `[0, windowDurationMs]`.
 */
internal data class ChapterWindow(
    val chapterIndex: Int,
    val windowStartMs: Long,
    val windowDurationMs: Long,
    val positionInWindowMs: Long,
)

/**
 * Computes the [ChapterWindow] containing [bookPositionMs].
 *
 * Chapter resolution (last chapter whose start is at-or-before the position) mirrors
 * `PlaybackManagerImpl.updateCurrentChapter` so the in-app chapter indicator and the
 * system-surface window never disagree about which chapter is "current". A chapter's
 * window end is the next chapter's start, or the book's total duration for the last
 * chapter — chapters have no stored end, and this keeps windows contiguous even if a
 * chapter's own `duration` field doesn't exactly match the gap to the next chapter.
 *
 * When [chapters] is empty, the whole book is presented as a single window — the
 * chapterless-book fallback required by every caller of this function.
 */
internal fun currentChapterWindow(
    chapters: List<Chapter>,
    bookPositionMs: Long,
    totalBookDurationMs: Long,
): ChapterWindow {
    if (chapters.isEmpty()) {
        return ChapterWindow(
            chapterIndex = -1,
            windowStartMs = 0L,
            windowDurationMs = totalBookDurationMs,
            positionInWindowMs = bookPositionMs.coerceIn(0L, totalBookDurationMs),
        )
    }

    val index = chapters.indexOfLast { it.startTime <= bookPositionMs }.coerceAtLeast(0)
    val chapter = chapters[index]
    val windowEndMs = chapters.getOrNull(index + 1)?.startTime ?: totalBookDurationMs
    val windowDurationMs = (windowEndMs - chapter.startTime).coerceAtLeast(0L)

    return ChapterWindow(
        chapterIndex = index,
        windowStartMs = chapter.startTime,
        windowDurationMs = windowDurationMs,
        positionInWindowMs = (bookPositionMs - chapter.startTime).coerceIn(0L, windowDurationMs),
    )
}

/**
 * Translates a chapter-relative seek target (as reported by a system surface's seek bar)
 * into a book-relative position, clamped to this window's bounds.
 */
internal fun ChapterWindow.seekTargetToBookPosition(chapterRelativePositionMs: Long): Long =
    windowStartMs + chapterRelativePositionMs.coerceIn(0L, windowDurationMs)

/** The time left in the window's chapter: content time, never divided by the speed (spec §3.2). */
internal val ChapterWindow.remainingMs: Long
    get() = windowDurationMs - positionInWindowMs

/**
 * What the system surfaces call this window's chapter: the book's own title, "Chapter N" when it has none, the
 * whole book when the book has no chapters. The shared [ChapterNaming] rule, so the card says what the mini
 * player says.
 */
internal fun ChapterWindow.chapterName(chapters: List<Chapter>): ChapterName =
    ChapterNaming.of(
        title = chapters.getOrNull(chapterIndex)?.title,
        number = chapterIndex + 1,
        isWholeBook = chapterIndex < 0,
    )

/**
 * The system media card's second line (spec §5.7): "Bridge Four · 8m left". Android 13+ draws it from the session's
 * artist, which is why the chapter, not the author, goes there; the author moves to the album.
 */
internal fun systemTrackLine(
    name: ChapterName,
    remainingMs: Long,
    words: SystemStrings,
): String {
    val chapter =
        when (name) {
            is ChapterName.Titled -> name.title
            is ChapterName.Untitled -> words.playerMiniChapter.format(Locale.getDefault(), name.number)
            ChapterName.WholeBook -> words.playerMiniWholeBook
        }
    val left = DurationFormatter.hoursMinutesOrUnderMinute(remainingMs.coerceAtLeast(0L).milliseconds)
    return words.playerSystemTrackLeft.format(Locale.getDefault(), chapter, left)
}

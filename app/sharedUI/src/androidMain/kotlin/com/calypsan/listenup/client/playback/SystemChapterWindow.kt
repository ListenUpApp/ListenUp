package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.localization.SystemStrings
import com.calypsan.listenup.client.presentation.player.ChapterName
import com.calypsan.listenup.client.presentation.player.ChapterNaming
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the system surfaces call this window's chapter: the book's own title, "Chapter N" when it has none, the
 * whole book when the book has no chapters. The shared [ChapterNaming] rule, so the card says what the mini
 * player says.
 */
internal fun ChapterWindow.chapterName(chapters: List<Chapter>): ChapterName =
    ChapterNaming.of(
        title = chapters.getOrNull(index)?.title,
        number = index + 1,
        isWholeBook = index < 0,
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

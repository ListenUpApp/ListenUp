package com.calypsan.listenup.client.playback

/** Current chapter information for notification and UI, derived by [chapterInfoAt] from a [ChapterWindow]. */
data class ChapterInfo(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val remainingMs: Long,
    val totalChapters: Int,
    val isGenericTitle: Boolean,
)

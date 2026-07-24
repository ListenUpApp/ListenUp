package com.calypsan.listenup.client.domain.playback

import com.calypsan.listenup.core.BookId

/**
 * Runtime construct for translating between book-relative positions and ExoPlayer coordinates.
 *
 * Built once when playback starts, cached for the session.
 * Rebuilt if playlist changes.
 *
 * Design decision: Book-relative positions are the single source of truth.
 * This class handles the translation to ExoPlayer's per-file model at runtime,
 * avoiding database sync complexity.
 */
data class PlaybackTimeline(
    val bookId: BookId,
    val totalDurationMs: Long,
    val files: List<FileSegment>,
) {
    /**
     * Represents a single audio file in the book's timeline.
     */
    data class FileSegment(
        val audioFileId: String, // "af-{hex}" from server
        val filename: String,
        val format: String, // "mp3", "m4b", etc.
        // Where this file starts in book timeline
        val startOffsetMs: Long,
        val durationMs: Long,
        val size: Long,
        val streamingUrl: String,
        // Local file path if downloaded, null otherwise
        val localPath: String?,
        // Index in ExoPlayer playlist
        val mediaItemIndex: Int,
    ) {
        /**
         * URI for playback - prefers local file over streaming.
         */
        val playbackUri: String
            get() = localPath?.let { "file://$it" } ?: streamingUrl

        /**
         * True if this file has been downloaded locally.
         */
        val isDownloaded: Boolean
            get() = localPath != null
    }

    /**
     * Convert book-relative position to ExoPlayer coordinates.
     *
     * O(n) where n is number of files (typically 1-20 for audiobooks).
     * Fast enough that we don't need caching.
     *
     * @param bookPositionMs Position in the book timeline (milliseconds)
     * @return ExoPlayer coordinates (media item index + position within file)
     */
    fun resolve(bookPositionMs: Long): PlaybackPosition {
        var accumulated = 0L
        for (file in files) {
            if (bookPositionMs < accumulated + file.durationMs) {
                return PlaybackPosition(
                    mediaItemIndex = file.mediaItemIndex,
                    positionInFileMs = bookPositionMs - accumulated,
                )
            }
            accumulated += file.durationMs
        }
        // Past end, return last position
        return if (files.isNotEmpty()) {
            PlaybackPosition(
                mediaItemIndex = files.lastIndex,
                positionInFileMs = files.last().durationMs,
            )
        } else {
            PlaybackPosition(0, 0)
        }
    }

    /**
     * Convert ExoPlayer position back to book-relative timeline.
     *
     * @param mediaItemIndex Index in ExoPlayer playlist
     * @param positionInFileMs Position within that file
     * @return Position in the book timeline (milliseconds)
     */
    fun toBookPosition(
        mediaItemIndex: Int,
        positionInFileMs: Long,
    ): Long {
        val offset = files.getOrNull(mediaItemIndex)?.startOffsetMs ?: 0L
        return offset + positionInFileMs
    }

    /**
     * Get the file segment at a specific media item index.
     */
    fun getFileAt(mediaItemIndex: Int): FileSegment? = files.getOrNull(mediaItemIndex)

    /**
     * Find the file containing a book-relative position.
     */
    fun findFileForPosition(bookPositionMs: Long): FileSegment? {
        val position = resolve(bookPositionMs)
        return getFileAt(position.mediaItemIndex)
    }

    /**
     * True if all files are downloaded for offline playback.
     */
    val isFullyDownloaded: Boolean
        get() = files.all { it.isDownloaded }

    companion object {
        /**
         * Build a [PlaybackTimeline] from pre-resolved per-file inputs.
         *
         * The caller is responsible for resolving the local path and the signed streaming URL
         * for each file. This method only assembles cumulative offsets — it never constructs
         * or templates any URL.
         *
         * @param bookId The book being played
         * @param files Pre-resolved per-file inputs (signed URLs + local paths already set)
         * @return Constructed timeline ready for playback
         */
        fun build(
            bookId: BookId,
            files: List<TimelineFileInput>,
        ): PlaybackTimeline {
            var cumulativeOffset = 0L
            val segments =
                files.mapIndexed { index, file ->
                    val segment =
                        FileSegment(
                            audioFileId = file.audioFileId,
                            filename = file.filename,
                            format = file.format,
                            startOffsetMs = cumulativeOffset,
                            durationMs = file.durationMs,
                            size = file.size,
                            streamingUrl = file.streamingUrl,
                            localPath = file.localPath,
                            mediaItemIndex = index,
                        )
                    cumulativeOffset += file.durationMs
                    segment
                }
            return PlaybackTimeline(bookId = bookId, totalDurationMs = cumulativeOffset, files = segments)
        }
    }
}

/** Pre-resolved per-file inputs for [PlaybackTimeline.build]: the caller resolves the
 *  local path and the signed streaming URL; the timeline only assembles offsets. */
data class TimelineFileInput(
    val audioFileId: String,
    val filename: String,
    val format: String,
    val durationMs: Long,
    val size: Long,
    val localPath: String?,
    /** Full signed streaming URL, or "" when the file is downloaded (local path wins). */
    val streamingUrl: String,
)

/**
 * Represents a position in ExoPlayer's coordinate system.
 *
 * ExoPlayer uses a playlist model where each MediaItem has its own timeline.
 * This class captures both the item index and the position within that item.
 */
data class PlaybackPosition(
    val mediaItemIndex: Int,
    val positionInFileMs: Long,
)

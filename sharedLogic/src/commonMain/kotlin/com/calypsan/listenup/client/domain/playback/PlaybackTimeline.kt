
package com.calypsan.listenup.client.domain.playback

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.AudioFile

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
        /** Builds the canonical server streaming URL for an audio file within a book. */
        private fun buildStreamingUrl(
            baseUrl: String,
            bookId: BookId,
            audioFileId: String,
        ): String = "$baseUrl/api/v1/books/${bookId.value}/audio/$audioFileId"

        /**
         * Build a PlaybackTimeline from audio files and a base streaming URL.
         *
         * @param bookId The book being played
         * @param audioFiles List of audio files from server
         * @param baseUrl Server base URL for building streaming URLs
         * @return Constructed timeline ready for playback
         */
        fun build(
            bookId: BookId,
            audioFiles: List<AudioFile>,
            baseUrl: String,
        ): PlaybackTimeline {
            var cumulativeOffset = 0L
            val segments =
                audioFiles.mapIndexed { index, file ->
                    val segment =
                        FileSegment(
                            audioFileId = file.id,
                            filename = file.filename,
                            format = file.format,
                            startOffsetMs = cumulativeOffset,
                            durationMs = file.duration,
                            size = file.size,
                            streamingUrl = buildStreamingUrl(baseUrl, bookId, file.id),
                            localPath = null,
                            mediaItemIndex = index,
                        )
                    cumulativeOffset += file.duration
                    segment
                }

            return PlaybackTimeline(
                bookId = bookId,
                totalDurationMs = cumulativeOffset,
                files = segments,
            )
        }

        /**
         * Build timeline with local path resolution for offline playback.
         *
         * @param bookId The book being played
         * @param audioFiles List of audio files from server
         * @param baseUrl Server base URL for building streaming URLs
         * @param resolveLocalPath Function to resolve local file paths for downloaded files
         * @return Constructed timeline with local paths where available
         */
        suspend fun buildWithLocalPaths(
            bookId: BookId,
            audioFiles: List<AudioFile>,
            baseUrl: String,
            resolveLocalPath: suspend (String) -> String?,
        ): PlaybackTimeline {
            var cumulativeOffset = 0L
            val segments =
                audioFiles.mapIndexed { index, file ->
                    val localPath = resolveLocalPath(file.id)
                    val segment =
                        FileSegment(
                            audioFileId = file.id,
                            filename = file.filename,
                            format = file.format,
                            startOffsetMs = cumulativeOffset,
                            durationMs = file.duration,
                            size = file.size,
                            streamingUrl = buildStreamingUrl(baseUrl, bookId, file.id),
                            localPath = localPath,
                            mediaItemIndex = index,
                        )
                    cumulativeOffset += file.duration
                    segment
                }

            return PlaybackTimeline(
                bookId = bookId,
                totalDurationMs = cumulativeOffset,
                files = segments,
            )
        }

        /**
         * Build timeline with local path resolution and direct streaming URLs.
         *
         * Non-local files use the direct URL pattern immediately -- ExoPlayer
         * buffers naturally so playback starts without waiting for transcoding.
         *
         * @param bookId The book being played
         * @param audioFiles List of audio files from server
         * @param baseUrl Server base URL for building streaming URLs
         * @param resolveLocalPath Function to resolve local file paths for downloaded files
         * @param prepareStream Unused, kept for API compatibility
         * @return Constructed timeline with direct streaming URLs
         */
        suspend fun buildWithTranscodeSupport(
            bookId: BookId,
            audioFiles: List<AudioFile>,
            baseUrl: String,
            resolveLocalPath: suspend (String) -> String?,
            @Suppress("UNUSED_PARAMETER")
            prepareStream: suspend (audioFileId: String, codec: String) -> StreamPrepareResult,
        ): PlaybackTimeline {
            var cumulativeOffset = 0L

            val segments =
                buildList {
                    for ((index, file) in audioFiles.withIndex()) {
                        val localPath = resolveLocalPath(file.id)

                        val streamingUrl = buildStreamingUrl(baseUrl, bookId, file.id)

                        add(
                            FileSegment(
                                audioFileId = file.id,
                                filename = file.filename,
                                format = file.format,
                                startOffsetMs = cumulativeOffset,
                                durationMs = file.duration,
                                size = file.size,
                                streamingUrl = streamingUrl,
                                localPath = localPath,
                                mediaItemIndex = index,
                            ),
                        )
                        cumulativeOffset += file.duration
                    }
                }

            return PlaybackTimeline(
                bookId = bookId,
                totalDurationMs = cumulativeOffset,
                files = segments,
            )
        }
    }
}

/**
 * Result from preparing a stream for playback.
 */
data class StreamPrepareResult(
    /** URL to stream (may be relative or absolute) */
    val streamUrl: String,
    /** Whether the stream is ready (false = transcoding in progress) */
    val ready: Boolean,
    /** Transcode job ID if not ready */
    val transcodeJobId: String? = null,
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

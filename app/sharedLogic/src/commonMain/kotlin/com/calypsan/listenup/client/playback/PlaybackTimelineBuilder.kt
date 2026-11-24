package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.TimelineFileInput
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * [PlaybackTimelineBuilder.build]'s result: the assembled [PlaybackTimeline] plus the
 * server-authoritative resume position ([PreparedPlayback.resumePositionMs]) and server conversion
 * availability from the same `prepare()` call. [serverResumePosition] is null on the
 * fully-downloaded path, where `prepare()` is skipped and the resume point resolves from Room alone.
 */
internal data class TimelineBuildResult(
    val timeline: PlaybackTimeline,
    val serverResumePosition: PlaybackPositionSyncPayload?,
    val serverConversion: ServerConversion,
)

/**
 * [PlaybackTimelineBuilder.build]'s per-file URL maps from one `prepare()` call. Both default
 * empty — [direct] is empty on the fully-downloaded and prepare-failed branches, and [hls] is
 * empty whenever no file in the response needed a transcode.
 *
 * On the fully-downloaded branch every file's [TimelineFileInput.localPath] carries playback. On
 * the prepare-failed branch only the downloaded files do: a missing file ends up with neither a
 * local path nor a URL, which is the honest gap the never-stranded fallback deliberately leaves
 * rather than papering over with a URL that would fail anyway.
 */
private data class PreparedUrls(
    val direct: Map<String, String> = emptyMap(),
    val hls: Map<String, String> = emptyMap(),
    /** When [direct] and [hls] stop working, epoch ms; null when nothing was signed or the server did not say. */
    val expireAtMs: Long? = null,
    /** What the prepare said about converting this device's unplayable files; covered when it was never asked. */
    val serverConversion: ServerConversion = ServerConversion.COVERED,
)

/**
 * Builds [PlaybackTimeline]s offline-first: resolves local downloaded paths first, and only
 * calls [PlaybackPrepareRepository.prepare] when one or more files are missing from disk.
 */
internal class PlaybackTimelineBuilder(
    private val downloadService: DownloadService,
    private val prepareRepository: PlaybackPrepareRepository,
) {
    /**
     * Build the [PlaybackTimeline]: resolve each file's local path, fetch signed
     * streaming URLs from the server only when not fully downloaded (offline-first),
     * then assemble the timeline. Fails if the server prepare call fails and nothing is downloaded: with the typed
     * cause kept, so a network failure reads as unreachable rather than as a book that can't play.
     *
     * The result also carries [TimelineBuildResult.serverResumePosition] — the
     * server-authoritative resume point from the same prepare() call — so the caller
     * can reconcile it against the local Room row instead of discarding it.
     */
    suspend fun build(
        bookId: BookId,
        domainAudioFiles: List<AudioFile>,
        serverUrl: String,
    ): AppResult<TimelineBuildResult> {
        // Batched instead of one Room query per file — a chaptered book can have 100-300 audio
        // files, and preparing playback is a <500ms budget. Sparse: only downloaded-and-verified
        // files are keys; `localPaths[file.id]` returns null for the rest either way.
        val localPaths: Map<String, String> = downloadService.getLocalPaths(domainAudioFiles.map { it.id })

        // Server-authoritative resume position, populated only when prepare() is called
        // (streaming path). Null on the fully-downloaded path — resume then resolves from Room alone.
        var serverResumePosition: PlaybackPositionSyncPayload? = null

        val downloadedCount = localPaths.size
        val missingCount = domainAudioFiles.size - downloadedCount

        val preparedUrls: PreparedUrls =
            if (missingCount == 0) {
                PreparedUrls() // fully downloaded — never touch the server (offline-first)
            } else {
                // Some files are missing → we need signed streaming URLs for them. prepare() returns
                // per-file URLs for the whole book; localPath still wins in playbackUri, so downloaded
                // files play from disk and only the missing ones stream.
                //
                // Routed through the bounded, self-healing engine so a dead-socket transport throw
                // becomes a typed, time-bounded AppResult.Failure instead of an unguarded exception
                // crossing the Swift Export seam as an opaque KotlinError.
                when (val result = prepareRepository.prepare(bookId)) {
                    is AppResult.Success -> {
                        serverResumePosition = result.data.resumePosition
                        PreparedUrls(
                            direct = result.data.audioFiles.associate { it.fileId to serverUrl + it.url },
                            hls =
                                result.data.audioFiles
                                    .mapNotNull { file -> file.hlsUrl?.let { file.fileId to serverUrl + it } }
                                    .toMap(),
                            expireAtMs = result.data.urlsExpireAt,
                            serverConversion =
                                if (result.data.transcodeUnavailable) {
                                    ServerConversion.UNAVAILABLE
                                } else {
                                    ServerConversion.COVERED
                                },
                        )
                    }

                    is AppResult.Failure -> {
                        // prepare() failed — almost always offline. Never-stranded: if SOME files are
                        // downloaded, build the timeline from the local files so the downloaded portion
                        // plays offline instead of failing the whole book. The missing files get no URL
                        // (honest gap — playbackUri "" — not a fake streaming URL that would fail anyway).
                        if (downloadedCount == 0) {
                            logger.error {
                                "prepare() failed for ${bookId.value} and no files are downloaded: ${result.error.message}"
                            }
                            return AppResult.Failure(result.error.asPlaybackFailure())
                        }
                        logger.warn {
                            "prepare() failed for ${bookId.value} (${result.error.code}); playing $downloadedCount " +
                                "downloaded file(s) offline, $missingCount unavailable until reconnect."
                        }
                        PreparedUrls()
                    }
                }
            }

        val timeline =
            PlaybackTimeline.build(
                bookId = bookId,
                files =
                    domainAudioFiles.map { file ->
                        TimelineFileInput(
                            audioFileId = file.id,
                            filename = file.filename,
                            format = file.format,
                            durationMs = file.duration,
                            size = file.size,
                            localPath = localPaths[file.id],
                            streamingUrl = preparedUrls.direct[file.id].orEmpty(), // "" when downloaded — localPath wins in playbackUri
                            hlsUrl = preparedUrls.hls[file.id],
                        )
                    },
                urlsExpireAtMs = preparedUrls.expireAtMs,
            )
        return AppResult.Success(
            TimelineBuildResult(
                timeline = timeline,
                serverResumePosition = serverResumePosition,
                serverConversion = preparedUrls.serverConversion,
            ),
        )
    }
}

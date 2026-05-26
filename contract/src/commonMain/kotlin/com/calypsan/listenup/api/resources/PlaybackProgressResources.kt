package com.calypsan.listenup.api.resources

import io.ktor.resources.Resource

/**
 * REST mirror of [com.calypsan.listenup.api.PlaybackProgressService]. All routes
 * live under `/api/v1/playback-progress`. Nested-class style per the
 * [LibraryResources] / [SearchResources] precedent.
 *
 * Every route requires JWT authentication. Read operations use `GET`; the batch
 * lookup uses `POST` because GET URI length limits make passing N book IDs as
 * query params unsafe at library scale.
 */
@Resource("/api/v1/playback-progress")
class PlaybackProgressResources {
    /**
     * REST mirror of [com.calypsan.listenup.api.PlaybackProgressService.listProgress]:
     * `GET /api/v1/playback-progress?limit=N` returns all of the caller's positions
     * (excluding tombstones, unordered, up to [limit]).
     */
    @Resource("")
    class List(
        val parent: PlaybackProgressResources = PlaybackProgressResources(),
        /** Max positions to return. Default 100; clamped to [1, 500] server-side. */
        val limit: Int = 100,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.PlaybackProgressService.getProgressBatch]:
     * `POST /api/v1/playback-progress/batch` with a `List<BookId>` body returns sparse
     * matches — only positions that exist for the requested book IDs.
     */
    @Resource("batch")
    class Batch(val parent: PlaybackProgressResources = PlaybackProgressResources())

    /**
     * REST mirror of [com.calypsan.listenup.api.PlaybackProgressService.getRecentlyListened]:
     * `GET /api/v1/playback-progress/recently-listened?limit=N` returns unfinished positions
     * ordered by `lastPlayedAt DESC` (continue-listening semantics).
     */
    @Resource("recently-listened")
    class RecentlyListened(
        val parent: PlaybackProgressResources = PlaybackProgressResources(),
        /** Max positions to return. Default 20; clamped to [1, 100] server-side. */
        val limit: Int = 20,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.PlaybackProgressService.getCompletedBooks]:
     * `GET /api/v1/playback-progress/completed?limit=N` returns finished positions
     * ordered by `lastPlayedAt DESC`.
     */
    @Resource("completed")
    class Completed(
        val parent: PlaybackProgressResources = PlaybackProgressResources(),
        /** Max positions to return. Default 50; clamped to [1, 500] server-side. */
        val limit: Int = 50,
    )
}

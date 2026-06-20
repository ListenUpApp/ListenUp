package com.calypsan.listenup.server.routes.resources

import com.calypsan.listenup.core.BookId
import io.ktor.resources.Resource

/**
 * REST mirror of [com.calypsan.listenup.api.PlaybackService.prepare]:
 * `GET /api/v1/playback/prepare/{bookId}` returns a
 * [com.calypsan.listenup.api.dto.PreparedPlayback].
 */
@Resource("/api/v1/playback/prepare/{bookId}")
class Prepare(
    val bookId: BookId,
)

/**
 * REST mirror of [com.calypsan.listenup.api.PlaybackService.getPosition]
 * (GET) and [com.calypsan.listenup.api.PlaybackService.recordPosition] (POST):
 * GET → `AppResult<PlaybackPositionSyncPayload?>`, POST with a
 * [com.calypsan.listenup.api.dto.RecordPositionRequest] body →
 * `AppResult<PlaybackPositionSyncPayload>`.
 */
@Resource("/api/v1/playback/position/{bookId}")
class Position(
    val bookId: BookId,
)

/**
 * REST mirror of [com.calypsan.listenup.api.PlaybackService.getStats]:
 * `GET /api/v1/playback/stats` returns the caller's [com.calypsan.listenup.api.sync.UserStatsSyncPayload]?.
 */
@Resource("/api/v1/playback/stats")
class Stats

/**
 * REST mirror of [com.calypsan.listenup.api.PlaybackService.recordListeningEvent]:
 * `POST /api/v1/playback/events` with a [com.calypsan.listenup.api.dto.RecordListeningEventRequest]
 * body returns the stored [com.calypsan.listenup.api.sync.ListeningEventSyncPayload].
 */
@Resource("/api/v1/playback/events")
class Events

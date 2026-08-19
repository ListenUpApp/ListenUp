package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.error.TranscodeError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.io.respondSeekable
import com.calypsan.listenup.server.transcode.HlsPlaylist
import com.calypsan.listenup.server.transcode.SegmentCache
import com.calypsan.listenup.server.transcode.SessionAdmission
import com.calypsan.listenup.server.transcode.TranscodeSession
import com.calypsan.listenup.server.transcode.TranscodeSessionEngine
import com.calypsan.listenup.server.transcode.TranscodeSettings
import com.calypsan.listenup.server.transcode.TranscoderAvailability
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.delay

/** `application/vnd.apple.mpegurl` — the type every HLS player expects for an `.m3u8`. */
private val M3U8 = ContentType("application", "vnd.apple.mpegurl")

/** Segments are raw ADTS AAC, as `-segment_format adts` writes them. */
private val AAC = ContentType("audio", "aac")

/** How long a segment request waits for the encoder to produce it before giving up. */
private const val SEGMENT_WAIT_MILLIS = 10_000L

/** How often that wait re-checks the cache. */
private const val SEGMENT_POLL_MILLIS = 100L

/**
 * HLS playlist and segment routes. NOT JWT-gated — the URL signature IS the auth, exactly as
 * [audioRoutes]. hls.js and `<audio>` cannot attach an Authorization header to a media URL, and a
 * cookie here would be a CSRF surface, so the same per-file time-boxed HMAC does the work.
 *
 * The signature covers `(userId, bookId, fileId)` — the *file*, not the URL — so one signed query
 * authorizes the master playlist, the media playlist, and every segment of that file. Playlists
 * forward the caller's own query string verbatim onto the URLs they emit, which is why a player
 * that only ever follows links stays authorized without understanding any of this.
 *
 * A forged or expired signature is 403. A book the caller cannot reach is 404 — never 403 — so the
 * response cannot be used to probe a private book's existence, matching [audioRoutes] exactly.
 */
internal fun Route.hlsRoutes(
    locator: AudioFileLocator,
    signer: AudioUrlSigner,
    roleLookup: UserRoleLookup,
    accessPolicy: BookAccessPolicy,
    engine: TranscodeSessionEngine,
    cache: SegmentCache,
    settings: TranscodeSettings,
    availability: TranscoderAvailability,
) {
    get("/api/v1/hls/{bookId}/{fileId}/master.m3u8") {
        call.serveMasterPlaylist(signer, roleLookup, accessPolicy, settings, availability)
    }

    get("/api/v1/hls/{bookId}/{fileId}/media.m3u8") {
        call.serveMediaPlaylist(locator, signer, roleLookup, accessPolicy, settings, availability)
    }

    get("/api/v1/hls/{bookId}/{fileId}/seg/{index}.aac") {
        call.serveSegment(locator, signer, roleLookup, accessPolicy, engine, cache, settings, availability)
    }
}

private suspend fun ApplicationCall.serveMasterPlaylist(
    signer: AudioUrlSigner,
    roleLookup: UserRoleLookup,
    accessPolicy: BookAccessPolicy,
    settings: TranscodeSettings,
    availability: TranscoderAvailability,
) {
    // Authorization only — a master playlist reveals nothing about the file, so it is answerable
    // without touching the database at all.
    authorizeHls(signer, roleLookup, accessPolicy) ?: return
    if (!canTranscode(settings, availability)) return respondTranscoderUnavailable()
    respondText(
        HlsPlaylist.renderMaster(
            mediaUrl = "media.m3u8?${request.queryString()}",
            bitrateKbps = settings.bitrateKbps,
        ),
        M3U8,
    )
}

private suspend fun ApplicationCall.serveMediaPlaylist(
    locator: AudioFileLocator,
    signer: AudioUrlSigner,
    roleLookup: UserRoleLookup,
    accessPolicy: BookAccessPolicy,
    settings: TranscodeSettings,
    availability: TranscoderAvailability,
) {
    val target = authorizeHls(signer, roleLookup, accessPolicy) ?: return
    if (!canTranscode(settings, availability)) return respondTranscoderUnavailable()
    val info = locator.transcodeInfo(target.bookId, target.fileId) ?: return respond(HttpStatusCode.NotFound)
    val plan = HlsPlaylist.plan(info.durationMs, info.sampleRate, settings.targetSegmentSeconds)
    val query = request.queryString()
    respondText(HlsPlaylist.render(plan) { index -> "seg/$index.aac?$query" }, M3U8)
}

private suspend fun ApplicationCall.serveSegment(
    locator: AudioFileLocator,
    signer: AudioUrlSigner,
    roleLookup: UserRoleLookup,
    accessPolicy: BookAccessPolicy,
    engine: TranscodeSessionEngine,
    cache: SegmentCache,
    settings: TranscodeSettings,
    availability: TranscoderAvailability,
) {
    val target = authorizeHls(signer, roleLookup, accessPolicy) ?: return
    if (!canTranscode(settings, availability)) return respondTranscoderUnavailable()
    val index = parameters["index"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return respond(HttpStatusCode.BadRequest)

    // Already encoded: serve it and never wake the encoder. This is the common case once a listener
    // is a few segments in, and it is what makes re-listening free. Completeness, not mere
    // existence — a segment FFmpeg is still writing exists, and serving it truncates the audio.
    if (cache.isComplete(target.bookId, target.fileId, index)) {
        return respondSeekable(cache.segmentPath(target.bookId, target.fileId, index), AAC)
    }

    val location = locator.locate(target.bookId, target.fileId) ?: return respond(HttpStatusCode.NotFound)
    val info = locator.transcodeInfo(target.bookId, target.fileId) ?: return respond(HttpStatusCode.NotFound)

    val session =
        TranscodeSession(
            bookId = target.bookId,
            fileId = target.fileId,
            sourcePath = location.path.toString(),
            sampleRate = info.sampleRate ?: HlsPlaylist.FALLBACK_SAMPLE_RATE,
            durationMs = info.durationMs,
        )
    when (engine.ensureRunning(session, index)) {
        SessionAdmission.Busy -> {
            respondAppResult<Unit>(AppResult.Failure(TranscodeError.TranscoderBusy()))
        }

        SessionAdmission.Admitted -> {
            if (awaitSegment(cache, target.bookId, target.fileId, index)) {
                respondSeekable(cache.segmentPath(target.bookId, target.fileId, index), AAC)
            } else {
                // The encoder was admitted but the bytes never arrived inside the window. The player
                // retries the same URL, which is why this is not a hard failure.
                respond(HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}

/** The `(bookId, fileId)` a verified request is for. */
private data class HlsTarget(
    val bookId: String,
    val fileId: String,
)

/**
 * Verifies the signature and the caller's access to the book, responding and returning null when
 * either fails. Mirrors [audioRoutes]: 403 for a bad signature, 404 for a book out of reach.
 */
private suspend fun ApplicationCall.authorizeHls(
    signer: AudioUrlSigner,
    roleLookup: UserRoleLookup,
    accessPolicy: BookAccessPolicy,
): HlsTarget? {
    val bookId = parameters["bookId"]
    val fileId = parameters["fileId"]
    if (bookId == null || fileId == null) {
        respond(HttpStatusCode.BadRequest)
        return null
    }
    val exp = request.queryParameters["exp"]?.toLongOrNull()
    val sig = request.queryParameters["sig"]
    val userId = request.queryParameters["u"]
    if (exp == null || sig == null || userId == null || !signer.verify(userId, bookId, fileId, exp, sig)) {
        respond(HttpStatusCode.Forbidden)
        return null
    }
    val role = roleLookup.roleOf(userId)
    if (role == null || !accessPolicy.canAccess(userId, role, bookId)) {
        respond(HttpStatusCode.NotFound)
        return null
    }
    return HlsTarget(bookId, fileId)
}

/** Whether this server can transcode at all right now: switched on, and with a probed encoder. */
private fun canTranscode(
    settings: TranscodeSettings,
    availability: TranscoderAvailability,
): Boolean = settings.enabled && availability.isAvailable

private suspend fun ApplicationCall.respondTranscoderUnavailable() =
    respondAppResult<Unit>(AppResult.Failure(TranscodeError.TranscoderUnavailable()))

/**
 * Waits for the encoder to *finish* segment [index], up to [SEGMENT_WAIT_MILLIS].
 *
 * Polling a directory is not elegant, but it is honest about what is being waited on: FFmpeg's
 * segment muxer gives no completion signal, and the alternative — watching the filesystem — buys
 * milliseconds on a path already bounded by encode speed.
 */
private suspend fun awaitSegment(
    cache: SegmentCache,
    bookId: String,
    fileId: String,
    index: Int,
): Boolean {
    var waited = 0L
    while (waited < SEGMENT_WAIT_MILLIS) {
        if (cache.isComplete(bookId, fileId, index)) return true
        delay(SEGMENT_POLL_MILLIS)
        waited += SEGMENT_POLL_MILLIS
    }
    return cache.isComplete(bookId, fileId, index)
}

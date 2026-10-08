package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.error.TranscodeError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.readBytes
import com.calypsan.listenup.server.io.respondSeekable
import com.calypsan.listenup.server.transcode.AdtsFrames
import com.calypsan.listenup.server.transcode.HlsPlaylist
import com.calypsan.listenup.server.transcode.SegmentCache
import com.calypsan.listenup.server.transcode.SessionAdmission
import com.calypsan.listenup.server.transcode.TranscodeCommand
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
import kotlinx.coroutines.withContext
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
        serveMasterPlaylist(
            call = call,
            signer = signer,
            roleLookup = roleLookup,
            accessPolicy = accessPolicy,
            settings = settings,
            availability = availability,
        )
    }

    get("/api/v1/hls/{bookId}/{fileId}/media.m3u8") {
        serveMediaPlaylist(
            call = call,
            locator = locator,
            signer = signer,
            roleLookup = roleLookup,
            accessPolicy = accessPolicy,
            settings = settings,
            availability = availability,
        )
    }

    get("/api/v1/hls/{bookId}/{fileId}/seg/{index}.aac") {
        serveSegment(
            call = call,
            locator = locator,
            signer = signer,
            roleLookup = roleLookup,
            accessPolicy = accessPolicy,
            engine = engine,
            cache = cache,
            settings = settings,
            availability = availability,
        )
    }
}

private suspend fun serveMasterPlaylist(
    call: ApplicationCall,
    signer: AudioUrlSigner,
    roleLookup: UserRoleLookup,
    accessPolicy: BookAccessPolicy,
    settings: TranscodeSettings,
    availability: TranscoderAvailability,
) {
    // Authorization only — a master playlist reveals nothing about the file, so it is answerable
    // without touching the database at all.
    authorizeHls(call = call, signer = signer, roleLookup = roleLookup, accessPolicy = accessPolicy)
        ?: return
    if (!canTranscode(settings, availability)) return respondTranscoderUnavailable(call)
    call.respondText(
        HlsPlaylist.renderMaster(
            mediaUrl = "media.m3u8?${call.request.queryString()}",
            bitrateKbps = settings.bitrateKbps,
        ),
        M3U8,
    )
}

private suspend fun serveMediaPlaylist(
    call: ApplicationCall,
    locator: AudioFileLocator,
    signer: AudioUrlSigner,
    roleLookup: UserRoleLookup,
    accessPolicy: BookAccessPolicy,
    settings: TranscodeSettings,
    availability: TranscoderAvailability,
) {
    val target =
        authorizeHls(call = call, signer = signer, roleLookup = roleLookup, accessPolicy = accessPolicy)
            ?: return
    if (!canTranscode(settings, availability)) return respondTranscoderUnavailable(call)
    val info = locator.transcodeInfo(target.bookId, target.fileId) ?: return call.respond(HttpStatusCode.NotFound)
    val plan = HlsPlaylist.plan(info.durationMs, info.sampleRate, settings.targetSegmentSeconds)
    val query = call.request.queryString()
    call.respondText(HlsPlaylist.render(plan) { index -> "seg/$index.aac?$query" }, M3U8)
}

private suspend fun serveSegment(
    call: ApplicationCall,
    locator: AudioFileLocator,
    signer: AudioUrlSigner,
    roleLookup: UserRoleLookup,
    accessPolicy: BookAccessPolicy,
    engine: TranscodeSessionEngine,
    cache: SegmentCache,
    settings: TranscodeSettings,
    availability: TranscoderAvailability,
) {
    val target =
        authorizeHls(call = call, signer = signer, roleLookup = roleLookup, accessPolicy = accessPolicy)
            ?: return
    if (!canTranscode(settings, availability)) return respondTranscoderUnavailable(call)
    val index =
        call.parameters["index"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return call.respond(HttpStatusCode.BadRequest)
    val info = locator.transcodeInfo(target.bookId, target.fileId) ?: return call.respond(HttpStatusCode.NotFound)
    val plan = HlsPlaylist.plan(info.durationMs, info.sampleRate, settings.targetSegmentSeconds)

    // Already encoded: serve it and never wake the encoder. This is the common case once a listener
    // is a few segments in, and it is what makes re-listening free. Completeness, not mere
    // existence — a segment FFmpeg is still writing exists, and serving it truncates the audio.
    if (cache.isComplete(target.bookId, target.fileId, index)) {
        return respondVerifiedSegment(
            call = call,
            cache = cache,
            target = target,
            index = index,
            plan = plan,
        )
    }

    val location = locator.locate(target.bookId, target.fileId) ?: return call.respond(HttpStatusCode.NotFound)
    val session =
        TranscodeSession(
            bookId = target.bookId,
            fileId = target.fileId,
            sourcePath = location.path.toString(),
            sampleRate = info.sampleRate ?: HlsPlaylist.FALLBACK_SAMPLE_RATE,
            durationMs = info.durationMs,
            codec = info.codec,
            codecProfile = info.codecProfile,
            channels = info.channels ?: TranscodeCommand.FALLBACK_CHANNELS,
        )
    when (engine.ensureRunning(session, index)) {
        SessionAdmission.Busy -> {
            respondAppResult<Unit>(call, AppResult.Failure(TranscodeError.TranscoderBusy()))
        }

        // No decoder this source can be trusted to, so nothing was started. Encoding it anyway
        // would serve audio with a fifth of the book silently missing.
        SessionAdmission.Unsupported -> {
            respondAppResult<Unit>(
                call,
                AppResult.Failure(
                    TranscodeError.TranscoderUnavailable(
                        debugInfo = "no FDK decoder for ${info.codec}/${info.codecProfile ?: "no profile"}",
                    ),
                ),
            )
        }

        SessionAdmission.Admitted -> {
            if (awaitSegment(cache = cache, bookId = target.bookId, fileId = target.fileId, index = index)) {
                respondVerifiedSegment(call = call, cache = cache, target = target, index = index, plan = plan)
            } else {
                // The encoder was admitted but the bytes never arrived inside the window. The player
                // retries the same URL, which is why this is not a hard failure.
                call.respond(HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}

/**
 * Serves a segment only if it holds the number of AAC frames the playlist promised for it.
 *
 * ⛔ **An encoder exiting 0 is not proof it worked.** FFmpeg handed an xHE-AAC source it cannot
 * fully decode drops the packets it cannot parse, writes short segments, and reports success —
 * measured at a median 23% of the audio missing across a real library. Counting frames is the only
 * check that catches that, and it catches every other cause of a short segment for free: a killed
 * encoder, a truncated write, a full disk.
 */
private suspend fun respondVerifiedSegment(
    call: ApplicationCall,
    cache: SegmentCache,
    target: HlsTarget,
    index: Int,
    plan: HlsPlaylist.Plan,
) {
    val path = cache.segmentPath(target.bookId, target.fileId, index)
    val frames = withContext(fileIoDispatcher) { AdtsFrames.countFrames(path.readBytes()) }
    val expected = plan.expectedFrames(index)
    if (frames == null || frames !in expected) {
        return respondAppResult<Unit>(
            call,
            AppResult.Failure(
                TranscodeError.TranscodeFailed(
                    debugInfo =
                        "segment $index of ${target.bookId}/${target.fileId} holds ${frames ?: "no countable"} frames, expected $expected",
                ),
            ),
        )
    }
    respondSeekable(call, path, AAC)
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
private suspend fun authorizeHls(
    call: ApplicationCall,
    signer: AudioUrlSigner,
    roleLookup: UserRoleLookup,
    accessPolicy: BookAccessPolicy,
): HlsTarget? {
    val bookId = call.parameters["bookId"]
    val fileId = call.parameters["fileId"]
    if (bookId == null || fileId == null) {
        call.respond(HttpStatusCode.BadRequest)
        return null
    }
    val exp = call.request.queryParameters["exp"]?.toLongOrNull()
    val sig = call.request.queryParameters["sig"]
    val userId = call.request.queryParameters["u"]
    if (exp == null ||
        sig == null ||
        userId == null ||
        !signer.verify(userId = userId, bookId = bookId, fileId = fileId, exp = exp, sig = sig)
    ) {
        call.respond(HttpStatusCode.Forbidden)
        return null
    }
    val role = roleLookup.roleOf(userId)
    if (role == null || !accessPolicy.canAccess(userId, role, bookId)) {
        call.respond(HttpStatusCode.NotFound)
        return null
    }
    return HlsTarget(bookId, fileId)
}

/** Whether this server can transcode at all right now: switched on, and with a probed encoder. */
private fun canTranscode(
    settings: TranscodeSettings,
    availability: TranscoderAvailability,
): Boolean = settings.enabled && availability.isAvailable

private suspend fun respondTranscoderUnavailable(call: ApplicationCall) =
    respondAppResult<Unit>(call, AppResult.Failure(TranscodeError.TranscoderUnavailable()))

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

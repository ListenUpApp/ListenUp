package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.profile.AvatarUploadResponse
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.io.MultipartPartTooLargeException
import com.calypsan.listenup.server.io.readBytes
import com.calypsan.listenup.server.io.receiveFirstFilePartBytes
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlin.time.Clock
import kotlinx.io.files.SystemFileSystem

private const val AVATAR_CACHE_SECONDS = 86_400 // 24h, mirrors Go
private const val AVATAR_TYPE_IMAGE = "image"

/** Maximum accepted avatar size. Shared with [com.calypsan.listenup.server.di.profileModule]. */
const val AVATAR_MAX_BYTES = 5L * 1024 * 1024 // 5 MiB

/**
 * Avatar binary surface (the JSON profile lives on ProfileService RPC):
 *  - POST /api/v1/profile/avatar  — own avatar, multipart; validates+stores via [imageStore], flips avatar_type=image.
 *  - GET  /api/v1/avatars/{userId} — raw image, authed (any user, for presence); 404 when none.
 *
 * Mount inside `authenticate(JWT_PROVIDER)`.
 */
fun Route.profileRoutes(
    sql: ListenUpDatabase,
    imageStore: ImageStore,
    publicProfileMaintainer: PublicProfileMaintainer,
    clock: Clock = Clock.System,
) {
    post("/api/v1/profile/avatar") {
        val principal = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val userId = principal.userId.value

        val data =
            try {
                call.receiveFirstFilePartBytes(AVATAR_MAX_BYTES)
            } catch (e: MultipartPartTooLargeException) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, "image exceeds ${e.limit} bytes")
            } ?: return@post call.respond(HttpStatusCode.BadRequest, "missing file part")

        // ImageStore validates by magic number, not the declared content type — the octet-stream
        // default only surfaces in its invalid-image error text, so no per-part content type is read.
        try {
            imageStore.store(userId, data, ContentType.Application.OctetStream.toString())
        } catch (e: ImageStore.InvalidImageException) {
            return@post call.respond(HttpStatusCode.UnprocessableEntity, e.message ?: "invalid image")
        }
        val now = clock.now().toEpochMilliseconds()
        suspendTransaction(sql) {
            sql.usersQueries.updateAvatarType(
                avatar_type = AVATAR_TYPE_IMAGE,
                avatar_updated_at = now,
                updated_at = now,
                id = userId,
            )
        }
        // Rebuild the public_profiles projection so the new avatar propagates through the sync
        // stream — both back to the uploader (its own UserAvatar reads the synced projection, not
        // the users row) and out to other clients. Mirrors ProfileServiceImpl.updateMyProfile.
        publicProfileMaintainer.refreshBestEffort(userId)
        // Return the server's avatar version so the client writes exact server truth into its
        // observed public_profiles row (no client-clock guess, no redundant re-download).
        call.respond(AvatarUploadResponse(avatarUpdatedAt = now))
    }

    get("/api/v1/avatars/{userId}") {
        val target = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val path = imageStore.pathFor(target) ?: return@get call.respond(HttpStatusCode.NotFound)
        if (SystemFileSystem.metadataOrNull(path)?.isRegularFile !=
            true
        ) {
            return@get call.respond(HttpStatusCode.NotFound)
        }
        call.response.header(HttpHeaders.CacheControl, "private, max-age=$AVATAR_CACHE_SECONDS")
        call.respondBytes(path.readBytes(), ContentType.parse(imageStore.contentTypeFor(path)))
    }
}

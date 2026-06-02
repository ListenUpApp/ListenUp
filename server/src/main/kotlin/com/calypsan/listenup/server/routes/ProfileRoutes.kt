package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray
import java.nio.file.Files
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private const val AVATAR_CACHE_SECONDS = 86_400 // 24h, mirrors Go
private const val AVATAR_TYPE_IMAGE = "image"

/**
 * Avatar binary surface (the JSON profile lives on ProfileService RPC):
 *  - POST /api/v1/profile/avatar  — own avatar, multipart; validates+stores via [imageStore], flips avatar_type=image.
 *  - GET  /api/v1/avatars/{userId} — raw image, authed (any user, for presence); 404 when none.
 *
 * Mount inside `authenticate(JWT_PROVIDER)`.
 */
fun Route.profileRoutes(
    db: Database,
    imageStore: ImageStore,
) {
    post("/api/v1/profile/avatar") {
        val principal = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val userId = principal.userId.value

        var bytes: ByteArray? = null
        var declared = ContentType.Application.OctetStream.toString()
        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem && bytes == null) {
                declared = part.contentType?.toString() ?: declared
                bytes = part.provider().toByteArray()
            }
            part.release()
        }
        val data = bytes ?: return@post call.respond(HttpStatusCode.BadRequest, "missing file part")

        try {
            imageStore.store(userId, data, declared)
        } catch (e: ImageStore.InvalidImageException) {
            return@post call.respond(HttpStatusCode.UnprocessableEntity, e.message ?: "invalid image")
        }
        suspendTransaction(db) {
            UserEntity.findById(userId)?.apply {
                avatarType = AVATAR_TYPE_IMAGE
                updatedAt = System.currentTimeMillis()
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/v1/avatars/{userId}") {
        val target = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val path = imageStore.pathFor(target) ?: return@get call.respond(HttpStatusCode.NotFound)
        if (!Files.isRegularFile(path)) return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "private, max-age=$AVATAR_CACHE_SECONDS")
        call.respondBytes(Files.readAllBytes(path), ContentType.parse(imageStore.contentTypeFor(path)))
    }
}

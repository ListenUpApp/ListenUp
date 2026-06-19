package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondPath
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.file.Path

/**
 * Routes that serve contributor photos and series cover images from local
 * storage. Mounted inside the JWT-authenticated scope in `Application.kt`.
 *
 * Range requests are honoured automatically via the [PartialContent] plugin
 * installed at application startup.
 *
 * Both [imagePath] / [coverPath] stored in the database are relative paths
 * (e.g. `contributors/{id}.jpg`, `series/{id}.jpg`) sandboxed under [imageHome] —
 * the same per-type layout the appliers write to. Paths containing `../` or
 * starting with `/` are rejected with 400 to prevent directory traversal.
 */
fun Route.metadataImageRoutes(
    contributorRepository: ContributorRepository,
    seriesRepository: SeriesRepository,
    imageHome: Path,
) {
    get("/api/v1/contributors/{id}/photo") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val contributor = contributorRepository.findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        val relPath = contributor.imagePath ?: return@get call.respond(HttpStatusCode.NotFound)
        val absolute = resolveSandboxed(imageHome, relPath) ?: return@get call.respond(HttpStatusCode.BadRequest)
        if (!absolute.toFile().isFile) return@get call.respond(HttpStatusCode.NotFound)
        call.respondPath(absolute)
    }

    get("/api/v1/series/{id}/cover") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val series = seriesRepository.findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        val relPath = series.coverPath ?: return@get call.respond(HttpStatusCode.NotFound)
        val absolute = resolveSandboxed(imageHome, relPath) ?: return@get call.respond(HttpStatusCode.BadRequest)
        if (!absolute.toFile().isFile) return@get call.respond(HttpStatusCode.NotFound)
        call.respondPath(absolute)
    }
}

/**
 * Resolves [relativePath] under [base], rejecting paths that would escape the
 * sandbox via `../` or an absolute `/` prefix. Returns null on rejection.
 */
private fun resolveSandboxed(
    base: Path,
    relativePath: String,
): Path? {
    if (relativePath.startsWith("/") || "../" in relativePath || relativePath == "..") return null
    return base.resolve(relativePath).normalize()
}

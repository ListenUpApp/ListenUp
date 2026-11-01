package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.io.respondSeekable
import com.calypsan.listenup.server.media.isManagedImagePath
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.io.files.Path

/**
 * Routes that serve contributor photos and series cover images from local
 * storage. Mount inside `authenticate(BLOB_READ_PROVIDER)` — both are displayed by an `<img src>`,
 * which has no way to set an `Authorization` header. The matching uploads are the `PUT`s in
 * [contributorRoutes] / [seriesRoutes], which stay behind `JWT_PROVIDER`.
 *
 * Range requests are honoured automatically via the [PartialContent] plugin
 * installed at application startup.
 *
 * The photo and cover paths stored in the database are relative paths of the one shape the server
 * mints — `contributors/<sha256>.<ext>`, `series/<sha256>.<ext>` — under [imageHome]. Any other
 * stored path (another folder, another extension, anything with `..`) is refused with 400 and
 * never read.
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
        val path =
            managedImagePath(imageHome, "contributors", relPath) ?: return@get call.respond(HttpStatusCode.BadRequest)
        respondSeekable(call, path, ContentType.defaultForFilePath(path.name))
    }

    get("/api/v1/series/{id}/cover") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val series = seriesRepository.findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        val relPath = series.coverPath ?: return@get call.respond(HttpStatusCode.NotFound)
        val path = managedImagePath(imageHome, "series", relPath) ?: return@get call.respond(HttpStatusCode.BadRequest)
        respondSeekable(call, path, ContentType.defaultForFilePath(path.name))
    }
}

/** Resolves [relativePath] under [base] when it is a managed image path in [folder]; null otherwise. */
private fun managedImagePath(
    base: Path,
    folder: String,
    relativePath: String,
): Path? = Path(base, relativePath).takeIf { isManagedImagePath(folder, relativePath) }

package com.calypsan.listenup.server.io

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.io.files.Path

/**
 * Streams the file at [path] with an explicit [contentType] as a byte-range-capable body — the
 * native-capable replacement for `respondFile` / `respondPath` / `LocalFileContent`.
 *
 * Cooperates with the `PartialContent` plugin for seek/resume (a media player's range requests get
 * `206` + `Content-Range`), and never loads the file whole (see [SeekableSourceContent]). Responds
 * `404 Not Found` when the file is missing. The caller sets any `Content-Disposition` / `ETag` /
 * `Last-Modified` headers before calling — exactly as the `respondFile` call sites do today.
 */
internal suspend fun ApplicationCall.respondSeekable(
    path: Path,
    contentType: ContentType,
) {
    val size = statFile(path)?.size
    if (size == null) {
        respond(HttpStatusCode.NotFound)
        return
    }
    respond(SeekableSourceContent(size, contentType) { openSeekableSource(path) })
}

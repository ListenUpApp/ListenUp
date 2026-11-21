package com.calypsan.listenup.server.io

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.toHttpDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.util.date.GMTDate
import kotlinx.io.files.Path

/**
 * Streams the file at [path] with an explicit [contentType] as a byte-range-capable body — the
 * native-capable replacement for `respondFile` / `respondPath` / `LocalFileContent`.
 *
 * Cooperates with the `PartialContent` plugin for seek/resume (a media player's range requests get
 * `206` + `Content-Range`), and never loads the file whole (see [SeekableSourceContent]). Responds
 * `404 Not Found` when the file is missing.
 *
 * Every response names the bytes it carries with `Last-Modified` and a strong `ETag` — [etag] when
 * the caller knows a better one (a stored content hash), otherwise [FileAttributes.entityTag]. That
 * is what makes an interrupted download resumable: NSURLSession produces no resume data without a
 * validator, and `PartialContent` reads these headers to honour `If-Range`, so a resume against a
 * file replaced since gets the whole new file instead of a splice of two. No `304`: no
 * `ConditionalHeaders` is installed, and a player must always be handed bytes it can play.
 */
internal suspend fun respondSeekable(
    call: ApplicationCall,
    path: Path,
    contentType: ContentType,
    etag: String? = null,
) {
    val attributes = statFile(path)
    if (attributes == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }
    call.response.headers.append(HttpHeaders.ETag, etag ?: attributes.entityTag())
    call.response.headers.append(HttpHeaders.LastModified, GMTDate(attributes.mtimeMs).toHttpDate())
    call.respond(SeekableSourceContent(attributes.size, contentType) { openSeekableSource(path) })
}

/**
 * A strong `ETag` for this version of a file: its size, modification time in milliseconds and inode.
 *
 * Read from the file itself rather than from anything the server remembers, so it survives a restart
 * and needs no rescan to notice a change. Rewriting a file moves its mtime; replacing it by rename (an
 * atomic write) also gives it a new inode, which catches the same-size swap inside one millisecond
 * that mtime alone would miss.
 */
internal fun FileAttributes.entityTag(): String =
    listOfNotNull(size, mtimeMs, inode).joinToString(separator = "-", prefix = "\"", postfix = "\"") { it.toString(16) }

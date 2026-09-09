package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.io.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Serves the bundled web client — the output of `app/webApp/web`'s `vite build`.
 *
 * The web client is a thick client: it owns a Room database in OPFS and searches a local FTS
 * index, so what the server hands it is a static shell, not rendered pages. Everything after
 * load happens over RPC.
 *
 * [webRoot] is the directory holding `index.html` and `assets/`. A `null` root mounts nothing,
 * which is the correct behaviour for a server built without the bundled client — better to 404
 * than to serve a broken shell.
 *
 * Ktor's `staticFiles` DSL is deliberately not used: it takes a `java.io.File` and so exists
 * only on JVM, while production ships the Kotlin/Native linuxX64 binary. This reads through
 * `SystemFileSystem`, the same way [com.calypsan.listenup.server.cover.CoverResponder] serves
 * filesystem covers, and therefore works on both targets.
 */
fun Route.webAppRoutes(webRoot: Path?) {
    if (webRoot == null) return

    install(CrossOriginIsolation)

    get("/{path...}") {
        val segments =
            call.parameters
                .getAll("path")
                .orEmpty()
                .filter { it.isNotEmpty() }
        val requested = segments.takeIf { it.isSafe() }?.let { resolveUnder(webRoot, it) }

        // Anything that is not a real file on disk falls back to the shell: client-side routing
        // means a deep link is a URL the user can reload or share, but only index.html exists.
        // A traversal attempt lands here too, which is exactly right — it is not an error worth
        // telling the caller about, it is simply not a file we serve.
        val fallback = resolveUnder(webRoot, listOf(INDEX))
        val target = requested?.takeIf { it.isRegularFile() } ?: fallback

        val bytes = withContext(fileIoDispatcher) { target.takeIf { it.isRegularFile() }?.readBytes() }
        if (bytes == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        // The content hash, not a timestamp: kotlinx-io's FileMetadata carries no modification
        // time, and the native target has no java.time — so `Last-Modified` is not on the table
        // and the ETag has to be the whole story. Same shape as CoverResponder.
        val etag = "\"${hashBytesSha256(bytes)}\""
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.response.headers.append(HttpHeaders.ETag, etag)
        // Vite content-hashes everything under assets/, so those URLs are immutable by
        // construction and a year is safe. The shell is the one file whose URL never changes,
        // so it must revalidate or a deploy is invisible until the cache expires. `no-cache`
        // means revalidate, not don't-store — the ETag above makes that a 304, not a re-download.
        //
        // `target !== fallback` is reference inequality on purpose: a request for a *missing*
        // `/assets/gone.js` is served the shell, and freezing the shell for a year under an
        // assets/ URL would strand every future visitor on a stale build. Do not simplify this
        // to a path check.
        call.response.headers.append(
            HttpHeaders.CacheControl,
            if (target !== fallback && segments.firstOrNull() == ASSETS_DIR) CACHE_IMMUTABLE else CACHE_REVALIDATE,
        )
        call.respondBytes(bytes, contentTypeFor(target.name))
    }
}

private const val INDEX = "index.html"
private const val ASSETS_DIR = "assets"
private const val CACHE_IMMUTABLE = "public, max-age=31536000, immutable"
private const val CACHE_REVALIDATE = "no-cache"

/**
 * Rejects any segment that could escape [webRoot].
 *
 * The path is rebuilt from routing segments rather than from the raw URL, so separators cannot
 * appear inside a segment — leaving `..` and `.` as the cases that matter. Checked explicitly
 * because this route hand-rolls static serving and therefore does not inherit Ktor's own
 * traversal protection.
 */
private fun List<String>.isSafe(): Boolean = none { it == ".." || it == "." }

private fun resolveUnder(
    root: Path,
    segments: List<String>,
): Path = segments.fold(root) { acc, segment -> Path(acc, segment) }

private fun Path.isRegularFile(): Boolean = SystemFileSystem.metadataOrNull(this)?.isRegularFile == true

/**
 * Maps the extensions a Vite build actually emits.
 *
 * `.wasm` matters most: served as anything else the browser refuses to stream-compile it, and
 * the SQLite build silently falls back or fails.
 */
private fun contentTypeFor(name: String): ContentType =
    when (name.substringAfterLast('.', "")) {
        "html" -> ContentType.Text.Html
        "js", "mjs" -> ContentType.Application.JavaScript
        "css" -> ContentType.Text.CSS
        "wasm" -> ContentType("application", "wasm")
        "json", "map" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "woff2" -> ContentType("font", "woff2")
        else -> ContentType.Application.OctetStream
    }

/**
 * Stamps the cross-origin isolation headers onto every response in this route subtree.
 *
 * OPFS requires `SharedArrayBuffer`, which the browser withholds unless the page is
 * cross-origin isolated, which requires exactly this header pair. `require-corp` governs
 * subresources as well as the document, so these apply to assets too — the wasm binary and the
 * SQLite worker are fetched rather than navigated to.
 *
 * The same pair is set for the dev and preview servers in `app/webApp/web/vite.config.ts`; if
 * one side changes, the other has to change with it.
 */
private val CrossOriginIsolation =
    createRouteScopedPlugin("CrossOriginIsolation") {
        onCallRespond { call ->
            call.response.headers.append("Cross-Origin-Opener-Policy", "same-origin")
            call.response.headers.append("Cross-Origin-Embedder-Policy", "require-corp")
        }
    }

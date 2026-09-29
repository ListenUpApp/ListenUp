package com.calypsan.listenup.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.io.files.Path

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
 *
 * Compression is the same story. `ktor-server-compression` publishes no linuxX64 artifact, so
 * the build precompresses instead (`web/scripts/precompress.mjs` writes `.br` and `.gz` beside
 * each file) and this route picks the best variant the request accepts — see [negotiateEncoding].
 * That is cheaper than runtime compression anyway: brotli at its highest setting, once per build,
 * and no CPU per request.
 */
fun Route.webAppRoutes(webRoot: Path?) = webAppRoutes(webRoot, SystemWebBundleFiles)

/** [webAppRoutes] over an explicit [files] seam, so a test can count disk visits. */
internal fun Route.webAppRoutes(
    webRoot: Path?,
    files: WebBundleFiles,
) {
    if (webRoot == null) return
    val bundle = WebBundle(webRoot, files)

    install(CrossOriginIsolation)

    get("/{path...}") {
        val segments =
            call.parameters
                .getAll("path")
                .orEmpty()
                .filter { it.isNotEmpty() }

        // Anything that is not a real file on disk falls back to the shell: client-side routing
        // means a deep link is a URL the user can reload or share, but only index.html exists.
        // A traversal attempt lands here too, which is exactly right — it is not an error worth
        // telling the caller about, it is simply not a file we serve.
        val requested = segments.takeIf { it.isSafe() && it.isNotEmpty() }?.let { bundle.lookup(it) }
        val file = requested ?: bundle.lookup(listOf(INDEX))
        if (file == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val encoding = negotiateEncoding(call.request.headers[HttpHeaders.AcceptEncoding], file.representations.keys)
        val representation = file.representations.getValue(encoding)

        // Validators and caching policy go on the 304 too: a revalidation refreshes the cache entry
        // it answers, and must say which representation it is vouching for.
        call.response.headers.append(HttpHeaders.ETag, representation.etag)
        if (file.representations.size > 1) call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
        // Vite content-hashes everything under assets/ (fonts included), so those URLs are
        // immutable by construction and a year is safe. The shell is the one file whose URL never
        // changes, so it must revalidate or a deploy is invisible until the cache expires.
        // `no-cache` means revalidate, not don't-store — the ETag above makes that a 304, not a
        // re-download.
        //
        // `requested != null`, not a path check: a request for a *missing* `/assets/gone.js` is
        // served the shell, and freezing the shell for a year under an assets/ URL would strand
        // every future visitor on a stale build. Do not simplify this to a path check.
        call.response.headers.append(
            HttpHeaders.CacheControl,
            if (requested != null && segments.firstOrNull() == ASSETS_DIR) CACHE_IMMUTABLE else CACHE_REVALIDATE,
        )

        // The content hash, not a timestamp: kotlinx-io's FileMetadata carries no modification
        // time, and the native target has no java.time — so `Last-Modified` is not on the table
        // and the ETag has to be the whole story. Computed once per file by WebBundle, so this
        // comparison costs nothing.
        if (call.request.headers[HttpHeaders.IfNoneMatch]?.namesEtag(representation.etag) == true) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }

        encoding.token?.let { call.response.headers.append(HttpHeaders.ContentEncoding, it) }
        call.respondBytes(bundle.bytesOf(representation), file.contentType)
    }
}

private const val INDEX = "index.html"
private const val ASSETS_DIR = "assets"
private const val CACHE_IMMUTABLE = "public, max-age=31536000, immutable"
private const val CACHE_REVALIDATE = "no-cache"

/** `If-None-Match` is a list (`"a", "b"`) or `*`; weak `W/` validators compare equal for a GET. */
private fun String.namesEtag(etag: String): Boolean =
    split(',').map { it.trim().removePrefix("W/") }.any { it == etag || it == "*" }

/**
 * Rejects any segment that could escape the web root.
 *
 * The path is rebuilt from routing segments rather than from the raw URL, so separators cannot
 * appear inside a segment — leaving `..` and `.` as the cases that matter. Checked explicitly
 * because this route hand-rolls static serving and therefore does not inherit Ktor's own
 * traversal protection.
 */
private fun List<String>.isSafe(): Boolean = none { it == ".." || it == "." }

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

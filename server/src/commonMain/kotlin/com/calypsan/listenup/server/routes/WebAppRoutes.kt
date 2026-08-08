package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.readBytes
import io.ktor.http.ContentType
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
        val target = requested?.takeIf { it.isRegularFile() } ?: resolveUnder(webRoot, listOf(INDEX))

        val bytes = withContext(fileIoDispatcher) { target.takeIf { it.isRegularFile() }?.readBytes() }
        if (bytes == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondBytes(bytes, contentTypeFor(target.name))
    }
}

private const val INDEX = "index.html"

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

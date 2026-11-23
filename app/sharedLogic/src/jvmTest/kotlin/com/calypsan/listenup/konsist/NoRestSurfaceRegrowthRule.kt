package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The REST surface cannot regrow. Every `@Resource` in the codebase must be one of the blob
 * endpoints frozen below.
 *
 * ListenUp once carried a speculative third-party REST mirror — 109 routes serving zero
 * consumers — governed by a Konsist rule that *mandated* a `@Resource` twin for every `@Rpc`
 * method. That rule and the surface it grew were deleted together; RPC is the whole application
 * surface now. This rule is the retirement ban that replaces it, the same shape as
 * [NoLegacySyncReferencesRule] for SSE: the deletion stays deleted because re-growing it fails
 * the build, not because everyone remembers the decision.
 *
 * The survivors are exactly the endpoints that carry **bytes**. A cacheable cover GET or a
 * multipart upload cannot be a JSON-RPC frame (kotlinx.rpc serializes structured values, not
 * arbitrary binary), so ADR 0006 keeps blobs on HTTP. That is the only justification this list
 * accepts — the client-side mirror of it is `@NonRpcTransport(BINARY_TRANSFER)`, whose sole
 * remaining reason exists for the same reason.
 *
 * **Adding an entry is a conscious edit, and the bar is "does it carry bytes?"** If a new
 * endpoint's answer is no, it belongs on an `@Rpc` service and adding it here is arguing with
 * the architecture. Note the parent classes below are pathless route roots with no handler of
 * their own; they exist only to nest the byte resources.
 *
 * `@Resource` is not the only way to declare a route: a plain `get("/…") { }` regrows REST just as
 * well. So a second census freezes every string-literal route — the path passed to
 * `get|post|put|patch|delete|head|route` in any server file that imports Ktor's server routing,
 * with `…RoutePaths.X` constants resolved from their objects — against [ALLOWED_LITERAL_ROUTES],
 * each entry with the reason it exists.
 */
class NoRestSurfaceRegrowthRule :
    FunSpec({
        // No assertScopeNotEmpty guard: this test asserts set EQUALITY against a non-empty frozen
        // list, so a collapsed scope makes `declared` empty and turns it RED. A count guard would
        // only restate what the equality already proves.
        test("@Resource is confined to the frozen blob surface — REST cannot regrow") {
            val declared =
                productionScope()
                    .classes(includeNested = true)
                    .filter { cls -> cls.annotations.any { it.name == "Resource" } }
                    .mapNotNull { it.fullyQualifiedName }
                    .toSet()

            declared shouldBe ALLOWED_BLOB_RESOURCES
        }

        // Equality against a non-empty frozen list again: a collapsed scope reads as RED, not green.
        test("string-literal routes are confined to the frozen byte, health and web-bundle surface") {
            literalRoutesIn(productionScope().files.map { it.path to it.text }) shouldBe ALLOWED_LITERAL_ROUTES.keys
        }
    })

/** `get|post|…|route` followed by its path argument — a string literal or a `…RoutePaths.NAME` constant — then `{`. */
private val ROUTE_CALL =
    Regex("""\b(get|post|put|patch|delete|head|route)\s*\(\s*(?:"([^"]*)"|(\w+RoutePaths)\.(\w+))\s*\)\s*\{""")

/** `const val NAME: String = "value"` inside a `…RoutePaths` object. */
private val ROUTE_PATH_CONSTANT = Regex("""const\s+val\s+(\w+)\s*(?::\s*String)?\s*=\s*"([^"]*)"""")

/**
 * `"VERB /path"` for every string-literal route declared in the server [files] (`path` to `text`) that import Ktor's
 * server routing, comments stripped. A `…RoutePaths.X` argument resolves through the constants object it names.
 */
internal fun literalRoutesIn(files: List<Pair<String, String>>): Set<String> {
    val constants =
        files
            .filter { (path, _) -> path.substringAfterLast('/').endsWith("RoutePaths.kt") }
            .flatMap { (path, text) ->
                val objectName = path.substringAfterLast('/').removeSuffix(".kt")
                ROUTE_PATH_CONSTANT.findAll(stripComments(text)).map { constant ->
                    "$objectName.${constant.groupValues[1]}" to constant.groupValues[2]
                }
            }.toMap()
    return files
        .filter { (path, text) -> "/server/src/" in path && "import io.ktor.server.routing." in text }
        .flatMap { (_, text) ->
            ROUTE_CALL.findAll(stripComments(text)).map { match ->
                val verb = match.groupValues[1]
                val constant = "${match.groupValues[3]}.${match.groupValues[4]}"
                val routePath = match.groupValues[2].ifEmpty { constants[constant] ?: "<unresolved $constant>" }
                "${verb.uppercase()} $routePath"
            }
        }.toSet()
}

/**
 * Every string-literal route permitted to exist, as `"VERB /path"`, each with why it is not an `@Rpc` method. Frozen
 * for equality like [ALLOWED_BLOB_RESOURCES]: an addition or a removal is a conscious edit.
 */
private val ALLOWED_LITERAL_ROUTES =
    mapOf(
        "GET /api/v1/audio/{bookId}/{fileId}" to "streams audio bytes, with range requests",
        "GET /api/v1/hls/{bookId}/{fileId}/master.m3u8" to "an HLS playlist the platform player fetches itself",
        "GET /api/v1/hls/{bookId}/{fileId}/media.m3u8" to "an HLS playlist the platform player fetches itself",
        "GET /api/v1/hls/{bookId}/{fileId}/seg/{index}.aac" to "HLS segment bytes",
        "GET /api/v1/cover-cast/{bookId}" to "cover bytes for a Cast receiver, which carries no Authorization header",
        "GET /api/v1/contributors/{id}/photo" to "contributor photo bytes",
        "GET /api/v1/series/{id}/cover" to "series cover bytes",
        "POST /api/v1/profile/avatar" to "a multipart avatar upload",
        "GET /api/v1/avatars/{userId}" to "avatar bytes",
        "GET /api/v1/admin/backups/{id}/download" to "backup archive bytes",
        "POST /api/v1/admin/backups/upload" to "a multipart backup archive upload",
        "POST /api/v1/admin/imports/abs/upload" to "a multipart Audiobookshelf archive upload",
        "POST /api/v1/admin/uploads" to "opens a resumable audiobook upload session — the byte protocol's first step",
        "POST /api/v1/admin/uploads/{sessionId}/file" to "a multipart audiobook file upload",
        "POST /api/v1/admin/uploads/{sessionId}/finalize" to "closes the byte upload protocol it belongs to",
        "DELETE /api/v1/admin/uploads/{sessionId}" to "abandons an upload session and its staged bytes",
        "GET /healthz" to "the health probe container orchestrators call; no client speaks RPC to it",
        "GET /healthz/whoami" to "the authenticated transport smoke probe on the native skeleton",
        "GET /{path...}" to "serves the web client's static bundle",
    )

/**
 * Every `@Resource` permitted to exist, fully qualified. Frozen deliberately: a *new* nested
 * resource under an existing parent is exactly the regrowth this rule exists to catch, so the
 * set is compared for equality rather than containment.
 *
 * A removal fails here too, which is intended — deleting a blob endpoint should be a conscious
 * edit, not a silent one.
 */
private val ALLOWED_BLOB_RESOURCES =
    setOf(
        // Book covers and document files.
        "com.calypsan.listenup.server.routes.resources.BookResources",
        "com.calypsan.listenup.server.routes.resources.BookResources.Cover",
        "com.calypsan.listenup.server.routes.resources.BookResources.Document",
        // Contributor photos.
        "com.calypsan.listenup.server.routes.resources.ContributorResources",
        "com.calypsan.listenup.server.routes.resources.ContributorResources.Image",
        // Series covers.
        "com.calypsan.listenup.server.routes.resources.SeriesResources",
        "com.calypsan.listenup.server.routes.resources.SeriesResources.Cover",
    )

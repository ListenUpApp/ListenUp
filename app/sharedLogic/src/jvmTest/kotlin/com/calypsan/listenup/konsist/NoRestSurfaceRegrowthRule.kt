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
 */
class NoRestSurfaceRegrowthRule :
    FunSpec({
        test("@Resource is confined to the frozen blob surface — REST cannot regrow") {
            val declared =
                productionScope()
                    .classes(includeNested = true)
                    .filter { cls -> cls.annotations.any { it.name == "Resource" } }
                    .mapNotNull { it.fullyQualifiedName }
                    .toSet()

            declared shouldBe ALLOWED_BLOB_RESOURCES
        }
    })

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

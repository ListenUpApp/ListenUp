package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Konsist guard pinning the wire-not-URL invariant for `CoverPayload`.
 *
 * A cover crosses the sync wire as a *content hash* plus a *source* enum —
 * never as a URL. The client constructs the cover URL itself from the book id
 * (`GET /api/v1/books/{id}/cover?v=<hash>`); the hash is the cache-buster. A
 * URL on the payload would couple the wire shape to a server host/scheme,
 * break offline clients that need a stable identity, and duplicate routing
 * knowledge the client already owns.
 *
 * The rule finds the `CoverPayload` type and asserts no property name contains
 * "url" (case-insensitive). The property set is asserted non-empty so the rule
 * cannot pass vacuously.
 */
class CoverPayloadCarriesNoUrl :
    FunSpec({

        test("CoverPayload carries no URL-shaped property") {
            val coverPayload =
                productionScope()
                    .classes()
                    .firstOrNull { it.name == "CoverPayload" }
                    ?: error("CoverPayload class not found in commonMain scope")

            // Guard against a vacuous pass: CoverPayload must declare properties.
            coverPayload.properties().shouldNotBeEmpty()

            val urlProperties =
                coverPayload
                    .properties()
                    .map { it.name }
                    .filter { it.contains("url", ignoreCase = true) }

            urlProperties.shouldBeEmpty()
        }
    })

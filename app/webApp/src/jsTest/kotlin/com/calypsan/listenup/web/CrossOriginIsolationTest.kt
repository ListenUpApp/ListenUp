package com.calypsan.listenup.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the first link of the chain the browser store depends on: OPFS needs
 * `SharedArrayBuffer`, which the browser exposes only under cross-origin isolation, which
 * requires COOP/COEP response headers. Vite serves the test page, so the headers come from
 * `app/webApp/web/vite.config.ts`.
 *
 * Asserted separately from the store proof on purpose: when this fails the environment is
 * wrong, not the database code, and the two failures should not look alike.
 */
class CrossOriginIsolationTest :
    FunSpec({
        test("the test page is cross-origin isolated") {
            js("window.crossOriginIsolated").unsafeCast<Boolean>() shouldBe true
        }

        test("SharedArrayBuffer is available") {
            js("typeof SharedArrayBuffer").toString() shouldBe "function"
        }
    })

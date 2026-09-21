package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.BrowserStoreEnvironment
import com.calypsan.listenup.client.diagnostics.checkBrowserStoreEnvironment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The browser lane serves COOP/COEP on the Vite dev/preview origin (`vite.config.ts`), so
 * every precondition holds here. This pins that the check agrees with reality rather than
 * reporting a false problem — a diagnostic that cries wolf is worse than none.
 *
 * ⛔ This lane can only ever see `Ready`, and that is the limitation worth naming: Kotest runs on
 * `localhost`, which browsers treat as trustworthy, so the degraded path is unreachable from
 * here. It is covered by `web/test/insecure-boot.mjs`, which loads the built app from a
 * non-loopback address. A spec that cannot fail is the shape that let the refusal ship.
 */
class BrowserStoreEnvironmentTest :
    FunSpec({
        test("the environment check reports Ready under the browser lane's headers") {
            checkBrowserStoreEnvironment() shouldBe BrowserStoreEnvironment.Ready
        }
    })

package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.BrowserStoreEnvironment
import com.calypsan.listenup.client.diagnostics.checkBrowserStoreEnvironment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The browser lane serves COOP/COEP on the Vite dev/preview origin (`vite.config.ts`), so
 * every precondition holds here. This pins that the check agrees with reality rather than
 * reporting a false problem — a diagnostic that cries wolf is worse than none.
 */
class BrowserStoreEnvironmentTest :
    FunSpec({
        test("the environment check reports Ready under the browser lane's headers") {
            checkBrowserStoreEnvironment() shouldBe BrowserStoreEnvironment.Ready
        }
    })

package com.calypsan.listenup.web

import com.calypsan.listenup.client.data.local.db.BrowserStoreEnvironment
import com.calypsan.listenup.client.data.local.db.checkBrowserStoreEnvironment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The karma lane serves COOP/COEP on localhost, so every precondition holds here. This
 * pins that the check agrees with reality rather than reporting a false problem — a
 * diagnostic that cries wolf is worse than none.
 */
class BrowserStoreEnvironmentTest :
    FunSpec({
        test("the environment check reports Ready under the karma lane's headers") {
            checkBrowserStoreEnvironment() shouldBe BrowserStoreEnvironment.Ready
        }
    })

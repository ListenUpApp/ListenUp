package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.client.data.settings.seedServerUrlFromOrigin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Proves [seedServerUrlFromOrigin] in isolation from Koin, storage, and the browser — it is a
 * pure function, so these specs need no server and run in both browser lanes (`pnpm test` and
 * `pnpm test:auth`) unconditionally, unlike [com.calypsan.listenup.web.RpcTransportTest].
 */
class OriginServerConfigTest :
    FunSpec({
        test("falls back to the origin when nothing is stored") {
            seedServerUrlFromOrigin(stored = null, origin = "https://library.example.com") shouldBe
                "https://library.example.com"
        }

        test("a stored URL wins over the origin") {
            seedServerUrlFromOrigin(
                stored = "https://elsewhere.example.com",
                origin = "https://library.example.com",
            ) shouldBe "https://elsewhere.example.com"
        }

        test("a blank stored URL is treated as absent, not as a choice") {
            seedServerUrlFromOrigin(stored = "", origin = "https://library.example.com") shouldBe
                "https://library.example.com"
        }
    })

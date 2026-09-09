package com.calypsan.listenup.server

import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.core.spec.style.FunSpec
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication

/**
 * Pins `server.trustProxy` — the opt-in that lets the per-IP rate-limit buckets key on the real
 * client address rather than a reverse proxy's single one.
 *
 * **The default is the load-bearing assertion.** With `XForwardedHeaders` installed, the address the
 * throttle keys on is whatever the caller *claims*, so on a directly-reachable server it converts a
 * per-IP throttle into a per-claimed-IP throttle — which is no throttle at all. Off unless an
 * operator has said the header comes from a proxy they trust.
 */
class TrustProxyConfigTest :
    FunSpec({
        test("XForwardedHeaders is not installed by default") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                startTheApplication()

                application.pluginOrNull(XForwardedHeaders).shouldBeNull()
            }
        }

        test("XForwardedHeaders is installed when server.trustProxy is true") {
            testApplication {
                useIsolatedTestConfig()
                environment { (config as MapApplicationConfig).put("server.trustProxy", "true") }
                application { module() }
                startTheApplication()

                application.pluginOrNull(XForwardedHeaders).shouldNotBeNull()
            }
        }
    })

/**
 * Forces the lazily-built application to actually start, so the plugin registry reflects
 * `module()`. Any request will do; the 404 path is the cheapest.
 */
private suspend fun io.ktor.server.testing.ApplicationTestBuilder.startTheApplication() {
    client.get("/this-path-does-not-exist")
}

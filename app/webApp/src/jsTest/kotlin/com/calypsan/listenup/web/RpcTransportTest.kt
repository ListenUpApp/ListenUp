package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.probeRpcTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * THE browser-can-open-the-socket proof: a real kotlinx.rpc `HttpClient` — `installKrpc()` over
 * `ktor-client-js`, nothing borrowed from the production `ApiClientFactory` — opens a WebSocket
 * from inside a browser against the page's OWN origin (`window.location.origin` + the public RPC
 * mount) and gets a **typed** [com.calypsan.listenup.api.error.AuthError] back.
 *
 * Same-origin on purpose, not the server's absolute URL: production never opens the socket
 * cross-origin (Ktor serves the bundle itself), and dev only works because Vite's `/api` proxy
 * (`ws: true`) reproduces that topology — see `vite.config.ts`. Dialing an absolute URL would
 * prove a topology that never ships.
 *
 * Login against credentials that cannot exist rather than a seeded happy path: it needs no
 * fixture user, and a transport that cannot carry a typed *failure* cannot carry a typed success
 * either — the failure case is the stronger, cheaper claim.
 *
 * This is the spec the whole web-client direction turns on. If `AuthError.InvalidCredentials`
 * does not survive the wire here, kotlinx.rpc over `ktor-client-js` is not viable and the plan
 * needs to be rethought before another line of browser client code is written.
 */
class RpcTransportTest :
    FunSpec({
        test("the RPC socket opens from the browser and a typed AuthError survives the wire") {
            // Guard, not the URL the probe dials: proves THIS run actually booted a server (via
            // `pnpm test:auth`, which injects it — see test/run-kotest.mjs), so "nobody started a
            // server" fails loudly here instead of masquerading as a broken socket below.
            val bootedServerUrl = js("window.__LU_SERVER_URL").unsafeCast<String?>()
            check(bootedServerUrl != null) {
                "no server was booted for this run — run `pnpm test:auth`, not `pnpm test`"
            }

            val probe =
                probeRpcTransport(
                    email = "rpc-transport-probe-${Random.nextInt(0, Int.MAX_VALUE)}@example.invalid",
                    password = "does-not-exist-password",
                )

            probe.socketOpened shouldBe true
            probe.errorCode shouldBe "AUTH_INVALID_CREDENTIALS"
        }
    })

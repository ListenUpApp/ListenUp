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
        // Both browser lanes compile ONE spec bundle, so this spec is also loaded by the
        // server-free `webKotest` lane, where it cannot possibly pass. It is therefore enabled
        // only when a server was actually booted.
        //
        // That would normally be a lane quietly running fewer tests — the exact failure this
        // project keeps discovered-test-count floors to prevent. The floors are what make it
        // safe: `webKotest` requires 211 and `pnpm test:auth` sets KOTEST_MIN_TESTS=217, so each
        // lane pins its own count and neither can silently drop a spec. Skipping here is a
        // declared configuration difference, not an escape hatch.
        val serverBooted = js("window.__LU_SERVER_URL").unsafeCast<String?>() != null

        test("the RPC socket opens from the browser and a typed AuthError survives the wire")
            .config(enabled = serverBooted) {
                val probe =
                    probeRpcTransport(
                        email = "rpc-transport-probe-${Random.nextInt(0, Int.MAX_VALUE)}@example.invalid",
                        password = "does-not-exist-password",
                    )

                probe.socketOpened shouldBe true
                probe.errorCode shouldBe "AUTH_INVALID_CREDENTIALS"
            }
    })

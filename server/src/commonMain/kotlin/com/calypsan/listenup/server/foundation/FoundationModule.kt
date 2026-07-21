package com.calypsan.listenup.server.foundation

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.SessionLiveness
import com.calypsan.listenup.server.plugins.installAppErrorStatusPages
import com.calypsan.listenup.server.plugins.installCallId
import com.calypsan.listenup.server.plugins.installJwtAuth
import com.calypsan.listenup.server.plugins.installRateLimiting
import io.ktor.serialization.kotlinx.json.json as ktorJson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.websocket.WebSockets
import kotlinx.rpc.krpc.ktor.server.Krpc

/** The minimal collaborators the foundation smoke routes need — no full DI graph. */
class FoundationDeps(
    val jwt: JwtConfiguration,
    val sessionLiveness: SessionLiveness,
)

/**
 * Installs the native-ready Ktor plugin stack — including the kotlinx.rpc transport over WebSocket
 * (`install(WebSockets)` + `install(Krpc)`) — plus the REST / auth smoke routes. The running
 * proof that the whole HTTP / RPC / auth substrate assembles and serves on Kotlin/Native.
 *
 * Deliberately minimal: it passes the smoke collaborators directly via [deps] rather than wiring
 * `KoinIsolated` and the full route graph. The production server uses
 * [com.calypsan.listenup.server.module] (jvmMain) — which additionally keeps `CallLogging`,
 * `PartialContent`, the full DI graph, and every domain route. This is the substrate those
 * verticals grow onto; the production native `main()` builds on [foundationServer].
 *
 * **RPC service registration is intentionally not done here.** The `guard(...)` decorator that every
 * RPC registration must wrap its impl in (it sanitises escaped exceptions into typed `AppError`
 * before they cross the wire) is KSP-generated on `kspJvm` — i.e. jvmMain-only — so a *guarded*
 * registration is impossible from commonMain until rpc-guard generates for native. This skeleton
 * therefore installs only the [Krpc] transport; callers register services. The native RPC smoke
 * proves the transport serves by registering an unguarded test ping via [foundationServer]'s
 * `configure` hook (test scope), and the production native `main()` registers the real
 * guarded services once rpc-guard is native.
 */
fun Application.installFoundation(deps: FoundationDeps) {
    install(ContentNegotiation) { ktorJson(contractJson) }
    install(Resources)
    install(WebSockets)
    install(Krpc)
    installCallId()
    installRateLimiting()
    installAppErrorStatusPages()
    installJwtAuth(deps.jwt, deps.sessionLiveness)

    mountSmokeRoutes()
}

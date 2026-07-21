package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.routes.resources.ScannerResources
import com.calypsan.listenup.server.scanner.ScannerServiceImpl
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * REST surface for the scanner. Two endpoints:
 *
 *  - `POST /api/v1/scan` — triggers a full scan. Returns an
 *    [AppResult]<[ScanResultSummary]>; concurrent calls receive
 *    `Failure(AlreadyRunning)`.
 *  - `GET /api/v1/scan/last` — returns the most recent
 *    [AppResult]<[ScanResult]>. Failure when no scan has run yet.
 *
 * Mounted inside the `authenticate(JWT_PROVIDER)` block — the auth gate is JWT-level, so
 * every endpoint here requires a valid access token. `POST /api/v1/scan` is additionally
 * ROOT/ADMIN-gated at the route **and** inside [ScannerServiceImpl.scanFull] (defense in depth —
 * the service gate is fail-closed on an absent principal); reading the last result stays visible
 * to any authenticated user.
 *
 * Live scan progress is RPC-only: `ScannerService.observeProgress()` on the authed kotlinx.rpc
 * mount (see [rpcRoutes]).
 */
fun Route.scannerRoutes(scannerService: ScannerService) {
    post<ScannerResources> {
        val p = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        if (p.role != UserRole.ROOT && p.role != UserRole.ADMIN) {
            return@post call.respond(HttpStatusCode.Forbidden)
        }
        // Bind the caller so the service-level admin gate (fail-closed on an absent principal)
        // sees the real role. Test fakes implement the contract interface, not the impl; for
        // them the route-level gate above is the enforcement, so fall through unscoped.
        val scoped = (scannerService as? ScannerServiceImpl)?.copyWith(PrincipalProvider { p }) ?: scannerService
        call.respondAppResult<ScanResultSummary>(scoped.scanFull())
    }
    get<ScannerResources.Last> {
        call.respondAppResult<ScanResult>(scannerService.lastScanResult())
    }
}

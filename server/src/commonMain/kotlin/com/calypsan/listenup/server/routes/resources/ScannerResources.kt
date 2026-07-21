package com.calypsan.listenup.server.routes.resources

import io.ktor.resources.Resource

/**
 * REST mirror of [com.calypsan.listenup.api.ScannerService]. The two
 * transports speak the same DTOs; clients pick the one they prefer.
 *
 *  - `POST /api/v1/scan` triggers a full scan.
 *  - `GET /api/v1/scan/last` returns the most recent [ScanResult].
 *
 * Live scan progress is RPC-only (`ScannerService.observeProgress`).
 */
@Resource("/api/v1/scan")
class ScannerResources {
    /** REST endpoint for ScannerService.lastScanResult — GET /api/v1/scan/last. */
    @Resource("last")
    class Last(
        val parent: ScannerResources = ScannerResources(),
    )
}

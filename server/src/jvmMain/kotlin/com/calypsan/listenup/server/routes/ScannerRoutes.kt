package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.routes.resources.ScannerResources
import io.ktor.server.application.call
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow

/**
 * REST + SSE surface for the scanner. Three endpoints:
 *
 *  - `POST /api/v1/scan` — triggers a full scan. Returns an
 *    [AppResult]<[ScanResultSummary]>; concurrent calls receive
 *    `Failure(AlreadyRunning)`.
 *  - `GET /api/v1/scan/last` — returns the most recent
 *    [AppResult]<[ScanResult]>. Failure when no scan has run yet.
 *  - `GET /sse/scan` — server-sent events; emits every [ScanEvent]
 *    serialized as JSON in the SSE `data` field.
 *
 * The kotlinx.rpc surface for the same service is mounted separately by
 * [rpcRoutes].
 */
fun Route.scannerRoutes(
    scannerService: ScannerService,
    events: Flow<ScanEvent>,
) {
    post<ScannerResources> {
        call.respondAppResult<ScanResultSummary>(scannerService.scanFull())
    }
    get<ScannerResources.Last> {
        call.respondAppResult<ScanResult>(scannerService.lastScanResult())
    }

    sse("/sse/scan") {
        events.collect { event ->
            send(
                ServerSentEvent(
                    event = event::class.simpleName,
                    data = contractJson.encodeToString(ScanEvent.serializer(), event),
                ),
            )
        }
    }
}


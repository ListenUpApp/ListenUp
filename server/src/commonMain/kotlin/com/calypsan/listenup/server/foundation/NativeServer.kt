package com.calypsan.listenup.server.foundation

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/**
 * Builds an embedded CIO server running the foundation skeleton. Compiles for linuxX64 — proving the
 * CIO engine links with the full plugin stack natively — and starts a real server (no test-host
 * shim) for the native RPC smoke. The production native `main()` (Phase 5) builds on this; it is not
 * the production entrypoint yet.
 *
 * [configure] runs after [installFoundation], on the same [Application] — the seam where callers
 * register RPC services on the installed [io.ktor.server.cio.CIO]/Krpc transport (the native RPC
 * smoke registers a test ping here; the production native `main()` will register the real guarded
 * services once rpc-guard is native — see [installFoundation]).
 */
fun foundationServer(
    port: Int,
    deps: FoundationDeps,
    configure: Application.() -> Unit = {},
) = embeddedServer(CIO, port = port) {
    installFoundation(deps)
    configure()
}

package com.calypsan.listenup.web

import org.w3c.dom.Worker
import org.w3c.dom.events.Event

/**
 * Spawns the SQLite web worker that backs `WebWorkerSQLiteDriver`.
 *
 * The worker script lives in `app/webApp/worker/` (see its provenance header). This module
 * declares no npm dependency for it: `web/scripts/sync-kotlin.mjs` copies the worker to
 * `kotlin/sqlite-wasm-worker/` beside the compiled Kotlin modules, so the
 * `new Worker(new URL("sqlite-wasm-worker/worker.js", import.meta.url))` specifier resolves
 * relative to the module that emits it, and the bundler in `web/` (Vite) takes it from there.
 *
 * `{ type: "module" }` is not optional: worker.js is an ES module (it imports
 * @sqlite.org/sqlite-wasm), and a worker spawned without it is parsed as a classic script,
 * which fails with "Cannot use import statement outside a module". Vite serves the module as
 * authored, so the omission surfaces immediately.
 *
 * `:app:sharedLogic` deliberately ships no worker script: the worker is an
 * application-provided resource, which is why the store takes a `Worker` rather than
 * creating one.
 *
 * The handle is returned with diagnostics attached. A worker that fails to parse or load never
 * sends a message at all, so the driver's only symptom is a reply that never arrives — the same
 * silent spinner an operator gets for a stripped COOP/COEP header. `onerror` and `onmessageerror`
 * are the only places that failure is nameable.
 */
fun createSqliteWorker(): Worker {
    val worker =
        js(
            """new Worker(new URL("sqlite-wasm-worker/worker.js", import.meta.url), { type: "module" })""",
        ).unsafeCast<Worker>()
    worker.onerror = { event ->
        console.error("SQLite worker error: $event")
    }
    // Through `asDynamic` because Kotlin's `Worker` external declaration predates
    // `onmessageerror` and never got it; the DOM property has been there since 2018.
    worker.asDynamic().onmessageerror = { event: Event ->
        console.error("SQLite worker message could not be deserialized: $event")
    }
    return worker
}

package com.calypsan.listenup.web

import org.w3c.dom.Worker

/**
 * Spawns the SQLite web worker that backs `WebWorkerSQLiteDriver`.
 *
 * The worker script is the local npm module `sqlite-wasm-worker` (webApp/worker — see its
 * provenance header), declared as an npm dependency in build.gradle.kts. Webpack statically
 * recognises the `new Worker(new URL(..., import.meta.url))` pattern, resolves the specifier
 * through node_modules, and emits worker.js — plus the @sqlite.org/sqlite-wasm import and
 * its .wasm sidecar — as a separate chunk in the bundle output.
 *
 * `{ type: "module" }` is not optional: worker.js is an ES module (it imports
 * @sqlite.org/sqlite-wasm), and a worker spawned without it is parsed as a classic script,
 * which fails with "Cannot use import statement outside a module". Webpack happened to paper
 * over the omission by rewriting the worker into a classic chunk; a bundler that serves the
 * module as authored — Vite does — surfaces it immediately.
 *
 * `:app:sharedLogic` deliberately ships no worker script: the worker is an
 * application-provided resource, which is why the store takes a `Worker` rather than
 * creating one.
 */
fun createSqliteWorker(): Worker =
    js(
        """new Worker(new URL("sqlite-wasm-worker/worker.js", import.meta.url), { type: "module" })""",
    ).unsafeCast<Worker>()

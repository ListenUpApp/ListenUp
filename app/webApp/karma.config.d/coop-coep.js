// Same cross-origin isolation requirement as the dev server (see
// webpack.config.d/coop-coep.js): without COOP/COEP the OPFS VFS inside the
// sqlite worker cannot use SharedArrayBuffer, and the runtime test would fail
// for reasons unrelated to Room. Also stretch the mocha timeout — the first
// test boots a wasm SQLite inside a worker, which can exceed the 2s default.
config.set({
    customHeaders: [
        {match: '.*', name: 'Cross-Origin-Opener-Policy', value: 'same-origin'},
        {match: '.*', name: 'Cross-Origin-Embedder-Policy', value: 'require-corp'},
    ],
    client: {
        mocha: {
            timeout: 30000,
        },
    },
});

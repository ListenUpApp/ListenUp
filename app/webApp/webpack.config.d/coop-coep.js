// sqlite-wasm's OPFS VFS needs SharedArrayBuffer, which browsers enable only under
// cross-origin isolation — so the dev server must send COOP/COEP. Production hosting needs
// the same two headers.
//
// Second half of the same story: those headers are only HONOURED on a "potentially
// trustworthy" origin — localhost, or real HTTPS. Open the dev server from another device
// over plain http://<ip>:8081 and the browser ignores them, sqlite-wasm cannot install the
// OPFS VFS, and the app only knows that the database would not open.
//
// So testing on a phone means fronting the dev server with HTTPS. Tailscale does it in one
// line, with a real certificate:
//
//     tailscale serve --bg http://127.0.0.1:8081    # undo: --https=443 off
//
// That request arrives carrying the tailnet hostname, which webpack-dev-server refuses
// unless allowed — hence allowedHosts. Dev server only; it never ships.
(function (config) {
    if (config.devServer) {
        config.devServer.headers = {
            'Cross-Origin-Opener-Policy': 'same-origin',
            'Cross-Origin-Embedder-Policy': 'require-corp',
        };
        config.devServer.allowedHosts = 'all';
        // Behind that proxy the page is on :443 while the dev server thinks it is on :8081,
        // so the hot-reload client dials wss://host:8081/ws and retries forever. Infer the
        // socket URL from the page instead.
        config.devServer.client = {webSocketURL: 'auto://0.0.0.0:0/ws'};
    }
})(config);

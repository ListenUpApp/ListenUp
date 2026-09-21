// Which SQLite VFS this browser can actually use.
//
// ⛔ Its own module so it can be tested without loading @sqlite.org/sqlite-wasm, which is a
// browser-targeted package that node cannot resolve from here. The decision is the part worth
// pinning: `insecure-boot.mjs` proves the app runs on a plain-http origin, but cannot prove the
// database opened — sabotage showed the shell, the banner and a usable screen all appear with
// this fallback removed, because a store that fails to open raises nothing the page can see.
//
// OPFS installs only when the page is cross-origin isolated on a trustworthy origin. On plain
// http over a LAN — the setup ListenUp recommends — it is absent, and requiring it meant no web
// client at all. An in-memory database needs none of that chain: every feature works and nothing
// survives a reload, which the app says out loud rather than hiding.
//
// `sqlite3.oo1.OpfsDb` being undefined IS the capability probe. Re-deriving the chain here
// (secure context → COOP/COEP → SharedArrayBuffer → OPFS) would be a second, drifting copy of
// what the library already decided when it installed its VFSs.
export function openDatabase(fileName, sqlite3) {
    if (typeof sqlite3.oo1.OpfsDb === 'function') {
        return new sqlite3.oo1.OpfsDb(fileName);
    }
    return new sqlite3.oo1.DB(':memory:');
}

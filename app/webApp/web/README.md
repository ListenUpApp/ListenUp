# `:app:webApp` — the JS side

KGP compiles Kotlin to ES modules and stops. Everything after that — dev server, bundling,
browser tests — lives here, as an ordinary JS project with an ordinary package manager.

## `src/web.css` is ours

It started as the ListenUp design project's web sheet and is **adapted, not mirrored**. The comps
are the brand reference and the starting point, not a spec to transcribe: prune what the app does
not use, add what it needs, and keep the tokens honest to the brand. `ClassContractTest` keeps the
Kotlin and the sheet agreeing with each other — it does not tie either to the design project.

## Why the dependencies are what they are

Kotlin libraries declare npm dependencies of their own, and the emitted ESM imports them by bare
specifier. Under KGP those arrived invisibly via generated `package.json` files; decoupled, we
declare them ourselves. That visibility is a feature, not a chore:

- **`ws`** — `ktor-client-core`'s JS output imports it unconditionally, even though a browser
  uses the native `WebSocket` and never touches it. `>= 8.21.0` is the floor that clears
  GHSA-96hv-2xvq-fx4p. Ktor pins it *exactly* at `8.20.1`, so under KGP the fix had to be a
  `resolution()` forced past that pin in the root `build.gradle.kts`; here it is an ordinary
  version range. (An alias to an empty stub would drop it from the browser bundle entirely —
  worth doing once something exercises the WebSocket path and can prove it safe.)
- **`@js-joda/core`** — `kotlinx-datetime`'s JS output imports it directly.
- **`@sqlite.org/sqlite-wasm`** — the SQLite build the OPFS worker wraps.

## Package manager

pnpm, and specifically pnpm ≥ 10: it refuses to run dependency install scripts unless they are
named in `pnpm-workspace.yaml`'s `allowBuilds`. Exactly one is allowed (`esbuild`, which links
Vite's platform binary); nothing else in this tree may execute code at install time. Yarn has no
equivalent default.

## Cross-origin isolation

OPFS needs `SharedArrayBuffer`, which needs COOP/COEP response headers. `vite.config.ts` is the
single place that sets them, feeding the dev server, the preview server and the test page. It
replaces `webpack.config.d/coop-coep.js` and `karma.config.d/coop-coep.js`.

**Production hosting needs the same two headers.** When Ktor serves the built assets, that route
must send them or the browser store silently loses OPFS.

## Tests

`test/run-kotest.mjs` runs the compiled Kotest bundle in Playwright and translates its TeamCity
service messages into a verdict.

Deliberately **not** Vitest. Kotest 6's JS engine registers *and* reports its own tests — there is
no `kotlin-test` adapter and no `describe`/`it` for another framework to host. Wrapping it in
Vitest would produce one opaque Vitest test around N Kotest tests and discard the TeamCity
stream. What karma actually provided was a browser, a page under the right headers, and a console
reader; that is what this replaces. Vitest earns a place here only if TS-authored tests appear.

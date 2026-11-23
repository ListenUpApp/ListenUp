# AGENTS.md — Web Client (`webApp`)

Operational rules for the `:app:webApp` browser client (`CLAUDE.md` is symlinked to this file). These complement the root `AGENTS.md`.

## Architecture & Build Toolchain

- **Real-DOM Compose HTML:** `:app:webApp` compiles Kotlin/JS with `useEsModules()` and hands ES modules to Vite (`app/webApp/web/`). It does **not** use `:app:sharedUI` (Canvas/Skia).
- **Local DB worker & server hosting:** The browser keeps its offline-capable local DB in SQLite WASM/OPFS (`app/webApp/worker/`), and `:server` serves the precompressed (`.br` / `.gz`) Vite `dist/` bundle at `/` via `WebAppRoutes`.
- **Always run web tests & builds through Gradle:**
  - `./gradlew :app:webApp:webKotest` — browser specs in Playwright Chromium
  - `./gradlew :app:webApp:webAuthKotest` — server-backed browser specs (requires `ffmpeg` on `PATH`)
  - `./gradlew :app:webApp:webBundle` — production Vite build + boot/CSP/precompression checks
  - `./gradlew :app:webApp:verifyWebLicences` — ensures `app/webApp/web/public/licences.json` matches Kotlin/JS + npm graphs
  - Never trust a bare `pnpm test`: it runs against stale compiled Kotlin/JS unless Gradle syncs it first.

## UI, Lifecycle & Hygiene

- **ViewModel lifecycles:** Because the browser has no Android `NavBackStackEntry`, bind shared ViewModels through `RouteSession` / `rememberSession` so scopes dispose cleanly on navigation.
- **No silent no-op callback defaults:** Do not declare `= {}` default callbacks on production `*Page` or form composables — missing action wiring should fail to compile, not turn into clickable dead buttons. Put default lambdas in test fixtures instead.
- **Dispose imperative DOM hooks:** Clear any `setTimeout`, listeners, or observer registrations in `onDispose`.
- **CSP alignment:** Keep the Content Security Policy in `WebAppRoutes.kt`, `app/webApp/web/vite.config.ts`, and the inline script SHA-256 hash in `app/webApp/web/index.html` aligned (`app/webApp/web/test/csp.test.mjs` pins them).

## Web Konsist Rules

Browser-specific rules live in `app/sharedLogic/src/jvmTest/kotlin/com/calypsan/listenup/konsist/`:

- `WebClassesAreDefinedRule`
- `WebClicksReachTheKeyboardRule`
- `WebDomCallsAreTypedRule`
- `WebSessionCoversItsViewModelRule`
- `WebSharedComponentsRule`
- `WebBookCoversCarryTheLockRule`

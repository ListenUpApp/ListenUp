# ListenUp Web Client — Architecture & Roadmap (Umbrella Design)

- **Date:** 2026-06-17
- **Status:** Approved (umbrella). Phase 1 detailed separately in `2026-06-17-web-client-phase-1-auth-design.md`.
- **Branch:** `feat/web-client` (worktree `/home/simonh/Code/lu-web`), branched from `main`. **Long-lived; not merged to `main` until the whole web client is complete.**
- **Target server:** the Kotlin `:server` module only. The Go server is explicitly ignored for this effort.

---

## 1. Context & Goals

ListenUp's first-party clients (Android / iOS / desktop, Compose Multiplatform) consume the server over **kotlinx.rpc**. We are adding a **web client**: lightweight, fast, server-rendered **HTMX + TailwindCSS**, **embedded in the Kotlin server** and shipped as part of it.

The web client is the **first serious consumer of the server's REST surface**. That gives this effort a dual purpose:

1. **Build the web app** — broad feature parity with the existing clients (minus the explicitly out-of-scope items below).
2. **Prove and document the REST + SSE API.** "We don't want to ship an API that can't be consumed." Every REST/SSE endpoint the web client touches gets a real, live consumer and **detailed, accurate OpenAPI/Swagger documentation built alongside it.** Documentation is a first-class output of every phase, not a trailing chore.

A secondary benefit: the web UI is a genuinely different UI paradigm (server-rendered HTML, streaming-only, no Room, no sync engine), so it is a strong test of the durability and versatility of the server, the contract, and the domain.

## 2. Non-Goals (permanent out-of-scope)

- **Downloads / offline / local caching of audio.** The web client is **streaming-only**.
- **Server discovery / mDNS / "server picker" screen.** The web app *is* served by the server, so the server URL is just the current origin. This screen and feature are cut entirely.
- Native-platform concerns (push notifications, OS permissions, etc.).

## 3. Architecture

### 3.1 Shape

One Ktor process, one port, one deployable — the existing Kotlin `:server`. The web UI is server-rendered HTMX written with the `kotlinx.html` DSL, styled with Tailwind. Its route handlers are a thin **Backend-For-Frontend (BFF)** layer that gets its data by calling the server's *own* REST/SSE over **loopback HTTP**, using a typed client built on the `:contract` `@Serializable` DTOs.

```
Browser ──cookie session + CSRF──▶ [ :web  (HTMX BFF, embedded in :server) ]
                                          │
                                          ▼  loopback HTTP, bearer token
                                   [ :server REST + SSE  /api/v1/... ]
                                          │
                                          ▼
                                   domain services / SQLite
```

### 3.2 Two auth hops

- **Browser ↔ BFF:** an opaque, high-entropy session id in an `HttpOnly`, `SameSite=Lax`, `Secure`-when-HTTPS cookie, plus CSRF protection on mutating requests. No token ever lives in JavaScript.
- **BFF ↔ REST:** a normal short-lived **bearer access token** the BFF holds for that session — i.e. the BFF authenticates to the API exactly as a third-party integrator would.

Detail of the session/token mechanics lives in the Phase 1 spec (it is the crux of Phase 1).

### 3.3 The `:web` module — structural BFF boundary

The web UI is a **new Gradle module `:web`** (`id("listenup.jvm")`), which depends on **`:contract` only — never on `:server`'s service layer**. Because it has no dependency on `:server`, it *physically cannot* bypass REST and reach into the domain services. The BFF boundary is therefore a **compile-time guarantee**, not a discipline. A Konsist rule asserts no `com.calypsan.listenup.server.*` import appears in `:web`.

`:server`'s `Application.installAppRoutes()` mounts the web routes via an `installWebUi(config)` entry point exposed by `:web`. `:server` depends on `:web` (composition root); `:web` does **not** depend on `:server`. Configuration (loopback base URL/port, cookie attributes) is passed from `:server` at mount time, since the server knows its own bind address.

### 3.4 Dogfooding rationale ("test both simultaneously")

With the BFF calling REST over loopback, **every page render is a live REST request**. You cannot ship a broken or undocumented REST endpoint without your own UI breaking. The small `restClient` in `:web` *is* the reference third-party client, which is what makes the OpenAPI/Swagger docs trustworthy rather than aspirational. SSE becomes first-class because the web app actually subscribes to it.

**Per-route off-ramp:** the loopback hop is a per-handler choice. If a specific hot path ever justifies a direct call, that one handler can change without affecting the architecture — but it becomes a deliberate, visible exception (and would require a dependency the module deliberately lacks), which keeps us honest about dogfooding by default.

**Cost (accepted):** a few sub-millisecond loopback HTTP calls per render (negligible at self-host concurrency on coroutine-based CIO), and a self-issued per-session bearer.

## 4. Cross-Cutting Requirements (every phase)

1. **REST ↔ RPC parity — incremental, web-driven.** Each phase brings the REST surface for the domains it touches up to **full parity** with the corresponding RPC interface, with every endpoint documented. Because the web client targets full feature parity overall, REST reaches full parity by the end — but every REST endpoint always has a **live consumer (the web UI)** the day it ships. No separate upfront parity sweep. (Early finding: REST appears closer to parity than expected — e.g. the auth surface already mirrors RPC 1:1.)
2. **OpenAPI/Swagger, built alongside.** Swagger UI served (e.g. `/api/docs`). The spec is extended every phase as endpoints are consumed, and its **accuracy is guarded by tests** (each documented path must exist and round-trip). Ktor's `openapi`/`swagger` plugins *serve* a spec; they do not auto-derive it from `@Resource`. See "Open decisions" for the generation-strategy question.
3. **Documentation as a deliverable.** Beyond OpenAPI: each phase leaves the consumed REST/SSE surface clearly documented in as much detail as practical.
4. **TailwindCSS** via the standalone CLI, content-scanning `:web`'s `.kt` files (Tailwind class names are string literals in `kotlinx.html`) → a single generated stylesheet served as a static resource. No npm/Node runtime dependency.
5. **Persistent player shell** (from the playback phase onward): the `<audio>` element survives HTMX navigation via `hx-preserve` / out-of-band swaps.
6. **Never Stranded:** every convenience (SSE live updates, etc.) has a manual fallback (poll / refresh).
7. **Project standards apply unchanged:** TDD (Kotest `FunSpec`, `testApplication`), `AppResult<T>` at boundaries, typed `AppError` mapping, file-per-concern (~300-line signal), Konsist architectural rules, contract round-trip tests.

## 5. Roadmap (Phase 1 fixed; later phases reorderable)

1. **Auth & the gate** *(Phase 1 — detailed separately)* — login, cookie session + CSRF, logout, first-run setup; register / pending-approval (first SSE consumer). Stands up ALL the plumbing: the `:web` module, the loopback REST client, Tailwind, OpenAPI/Swagger, app-shell skeleton, static-asset serving.
2. **Library viewer** — home + continue-listening, library list/grid (books), book detail (read-only metadata + covers). Brings books/library REST to parity.
3. **Playback** — browser streaming (`<audio>` + signed URLs + range requests), persistent player shell, resume/progress, speed / skip / chapters / sleep-timer. The hardest path.
4. **Discovery & taxonomy** — full search, series, contributors (authors/narrators), genres / moods / tags.
5. **Shelves, collections & social** — shelves, collections, profiles, discover / activity / leaderboards (dogfoods SSE broadly).
6. **Admin & management** — users, invites / approvals, library / scan control, metadata editing / enrichment, ABS import, backups. (Invite-code *claiming* from Phase 1 is parked here pending REST confirmation.)
7. **Real-time polish** — app-wide SSE live updates (position, presence) + reconnection resilience.

## 6. Branch & Delivery Strategy

- Worktree `/home/simonh/Code/lu-web`, branch `feat/web-client`, branched from `main`.
- **Never merged to `main` until the entire web client is complete.** Design docs and every commit live on the branch.
- Specs are committed **inside the git repo** at `docs/superpowers/specs/` (the umbrella `docs/` used by other specs is not version-controlled; for this isolated effort the design travels with the branch).
- Each phase is its own spec → plan → implementation cycle, committed to this branch.

## 7. Open Decisions / Risks

- **OpenAPI generation strategy.** Phase 1 hand-authors the (small) auth slice and guards it with an accuracy test. Before the spec grows large (by ~Phase 4–6) we must decide: keep hand-authoring with test guards, or invest in code-first generation from `@Resource` + `@Serializable` DTOs. Flagged as an early decision.
- **Web-session persistence.** Phase 1 uses an in-memory web-session store (server restart logs web users out; re-login required). Encrypted persistence is a possible later enhancement.
- **REST completeness gating parity.** If a later domain's REST surface turns out thinner than its RPC surface, that phase absorbs the work of bringing REST up to parity (consistent with the incremental-parity requirement).
- **Audio codec coverage in the browser.** Native `<audio>` covers common codecs (MP3/M4B/AAC); codecs browsers can't play natively need a transcode path. To be resolved in the playback phase.

# Web Client — Phase 1: Auth & the Gate (Detailed Design)

- **Date:** 2026-06-17
- **Status:** Approved design; ready for implementation planning.
- **Umbrella:** see `2026-06-17-web-client-architecture-design.md` for architecture, cross-cutting requirements, and roadmap.
- **Branch:** `feat/web-client` (worktree `/home/simonh/Code/lu-web`).

Phase 1 establishes the authentication gate **and** all the web-tier plumbing every later phase depends on: the `:web` module, the loopback REST client, the browser session model, Tailwind, OpenAPI/Swagger, the app-shell skeleton, and static-asset serving.

---

## 1. Starting Point — What Already Exists

The Kotlin server's **auth REST surface already exists and mirrors the `AuthService` RPC interface 1:1.** Phase 1's parity contribution for auth is therefore mostly **verification + documentation**, not new endpoints.

### 1.1 Existing auth REST endpoints
(`client/server/src/main/kotlin/com/calypsan/listenup/server/routes/AuthRoutes.kt`, resources in `client/contract/.../api/resources/AuthResources.kt`)

| Method | Path | Request | Response | Auth |
|---|---|---|---|---|
| POST | `/api/v1/auth/login` | `LoginRequest` | `AppResult<AuthSession>` | public |
| POST | `/api/v1/auth/register` | `RegisterRequest` | `AppResult<RegisterResult>` | public |
| POST | `/api/v1/auth/setup` | `RegisterRequest` | `AppResult<AuthSession>` | public |
| POST | `/api/v1/auth/refresh` | `RefreshRequest` | `AppResult<AuthSession>` | public |
| POST | `/api/v1/auth/logout` | — | `AppResult<Unit>` | JWT |
| POST | `/api/v1/auth/logout/all` | — | `AppResult<Unit>` | JWT |
| GET | `/api/v1/auth/current-user` | — | `AppResult<User>` | JWT |
| GET | `/api/v1/auth/sessions` | — | `AppResult<List<SessionSummary>>` | JWT |
| DELETE | `/api/v1/auth/sessions/{id}` | — | `AppResult<Unit>` | JWT |
| GET | `/api/v1/instance` | — | `AppResult<ServerInfo>` | public |
| GET | `/api/v1/auth/registration-status/{userId}` | — | `RegistrationStatusEvent` | public |
| GET | `/api/v1/auth/registration-status/{userId}/stream` | — | SSE of `RegistrationStatusEvent` | public |

### 1.2 Key DTOs (contract — `client/contract/.../api/dto/auth/`)

```kotlin
data class AuthSession(
    val accessToken: AccessToken, val accessTokenExpiresAt: Long,   // unix millis
    val refreshToken: RefreshToken, val refreshTokenExpiresAt: Long,
    val sessionId: SessionId, val user: User,
)
sealed interface RegisterResult {
    data class Authenticated(val session: AuthSession) : RegisterResult
    data class PendingApproval(val userId: UserId) : RegisterResult
}
data class ServerInfo(
    val name: String, val version: String, val apiVersion: String,
    val setupRequired: Boolean,                 // true → route to /setup
    val registrationPolicy: RegistrationPolicy, // OPEN | APPROVAL_QUEUE | CLOSED
    val remoteUrl: String?, val instanceId: String,
)
enum class UserStatus { ACTIVE, PENDING_APPROVAL, DENIED }
enum class UserRole { ROOT, ADMIN, MEMBER }
// RegistrationStatusEvent.status ∈ {"pending","approved","denied"}
```
Token types are value classes: `AccessToken`, `RefreshToken`, `SessionId`, `UserId`.

### 1.3 Server auth internals (for the loopback/refresh mechanics)
- JWT HS256, claims `sub`=userId, `jti`=sessionId, `role`. **Access TTL 15 min.**
- `SessionService`: **refresh TTL 30 days; refresh tokens rotate on use; replay → family revoke.** Refresh tokens are stored HMAC-hashed server-side (plaintext is not recoverable from the server).
- JWT provider name constant `JWT_PROVIDER = "jwt"`.
- `Application.kt` integration points: `installCorePlugins()` (ContentNegotiation/Resources/SSE/Krpc/PartialContent/AutoHeadResponse already installed), `installJwtAuth(jwt, sessions)`, `installAppRoutes(homeDir)`.

## 2. What Phase 1 Builds

### 2.1 The `:web` module
- New Gradle module `:web` with `id("listenup.jvm")`, `group = com.calypsan.listenup`.
- **Dependencies: `:contract` only** (plus Ktor server-html/htmx, Ktor client, kotlinx.html, kotlinx.serialization). **No dependency on `:server`.**
- Exposes `fun Application.installWebUi(config: WebUiConfig)` that installs the web routes, static assets, and the browser-session plumbing.
- `:server` adds `implementation(projects.web)` and calls `installWebUi(...)` from `installAppRoutes()`, passing `WebUiConfig(loopbackBaseUrl, cookieConfig, ...)`.
- Konsist rule: no `com.calypsan.listenup.server.*` import in `:web`.

### 2.2 Catalog additions (`client/gradle/libs.versions.toml`)
Add: `ktor-server-html-builder`, `kotlinx-html`, `ktor-htmx`, `ktor-htmx-html` (HTMX integration is experimental Ktor API), and `ktor-server-csrf`. Already present and reused: `ktor-client-core`, `ktor-client-cio`, `ktor-server-resources`, `ktor-server-sse`, `ktor-server-openapi`, `ktor-server-swagger`. **OpenAPI generation:** add the code-first library chosen by the §5 spike (candidates: tegral-openapi, Papsign/ktor-openapi-tools, or a kotlinx-serialization JSON-Schema + custom DSL hybrid that needs no new dependency). **CSRF:** the `ktor-server-csrf` plugin (chosen over a hand-rolled filter — don't hand-roll security).

### 2.3 Loopback REST client
- A typed client in `:web` over `ktor-client-cio`, base URL = `WebUiConfig.loopbackBaseUrl`.
- Methods mirror the consumed REST endpoints, typed with `:contract` DTOs.
- Sends `Authorization: Bearer <accessToken>` from the current web session.
- Deserializes `AppResult.Failure` bodies into the typed `AppError` hierarchy so handlers can render `AppError.message` (already user-facing) directly.

### 2.4 Static assets & app shell
- Serve vendored `htmx.min.js` and the generated Tailwind stylesheet as static resources (e.g. under `/assets`). No Kotlin/JS module.
- Minimal app-shell layout (`<head>` with Tailwind CSS, htmx script, CSRF meta tag; `<body>` shell). Pre-login pages use minimal chrome; post-login lands on a placeholder home (Phase 2 fills it).

## 3. The Browser Session Model (the crux)

### 3.1 Decision: server-side in-memory web-session store + single-flight refresh
- Cookie holds **only an opaque, high-entropy session id** (`HttpOnly`, `SameSite=Lax`, `Secure` when HTTPS). No secret in the cookie → no cookie encryption needed and no `ktor-server-sessions` dependency; set/read the cookie directly.
- A `WebSessionStore` in `:web` maps `cookieId → WebSession { sessionId, refreshToken (plaintext, RAM only), cachedAccessToken, accessExpiresAt, userId, role }`.
- **Why server-side, not a stateless encrypted cookie:** refresh tokens **rotate on every use**. With a stateless cookie, two concurrent in-flight browser requests could present the same refresh token → replay detection → **family revoke → surprise logout**. A server-side store lets us **single-flight** the refresh per session so rotation is safe.
- **Cost (accepted, per self-host threat model):** server restart clears the store → web users re-login. Plaintext refresh tokens never touch disk. Encrypted persistence is a later option (umbrella "Open decisions").

### 3.2 `requireWebSession` interceptor
Guards protected web routes:
1. Read cookie → load `WebSession` (redirect `/login` if absent).
2. If `cachedAccessToken` valid → use it.
3. If expired → **single-flight** loopback `POST /api/v1/auth/refresh` with the stored refresh token; persist the rotated refresh token + new access token; redirect `/login` if refresh fails.
4. Expose the access token (+ user/role) to the handler.

Public web routes (`/login`, `/setup`, `/register`, `/pending`) skip the interceptor.

### 3.3 CSRF
`SameSite=Lax` + an **origin/referer check** + a **double-submit CSRF token** on every mutating web route. The token is issued on the form GET (so even login-CSRF is covered). Implemented via the **`ktor-server-csrf` plugin**.

## 4. Screens & Flows

All pages rendered with `kotlinx.html` + Tailwind; form posts target web (BFF) routes which call REST over loopback.

- **Entry** `GET /` → fetch `ServerInfo`. `setupRequired` → redirect `/setup`. Else valid session → home placeholder; otherwise → `/login`.
- **Login** `GET /login` (form + CSRF) → `POST /login` → loopback `auth/login`. Success: create web session, set cookie, `HX-Redirect` to home. Failure: re-render form with `AppError.message`.
- **First-run setup** `GET/POST /setup` → loopback `auth/setup` (`RegisterRequest`) → `AuthSession` → create session → home. Redirect to `/login` if `!setupRequired`.
- **Register** `GET/POST /register` (hidden/disabled when policy `CLOSED`) → loopback `auth/register` → `RegisterResult.Authenticated` → home; `RegisterResult.PendingApproval` → `/pending?userId=…`.
- **Pending approval** `/pending` → **first SSE consumer**: subscribe to `auth/registration-status/{userId}/stream` via the HTMX SSE extension, with a **poll fallback** (`hx-trigger="every Ns"` against the pull endpoint) per Never-Stranded. `approved` → redirect login; `denied` → show reason.
- **Logout** `POST /logout` → loopback `auth/logout` (bearer) → clear store + expire cookie → `/login`.
- **Active sessions** — list devices via `auth/sessions` and revoke via `DELETE auth/sessions/{id}`. Included in Phase 1; exercises those endpoints for parity + docs.

## 5. OpenAPI / Swagger — code-first generation (FIRST Phase 1 task)
Documentation is **generated from the code**, not hand-written. This is the **first task in Phase 1**, done as a de-risking spike *before* the auth screens are built, because every later phase and the parity requirement depend on the pattern it establishes.

- **Spike goal:** wire code-first OpenAPI generation to the **existing** auth endpoints (§1.1) and confirm accurate Swagger UI at `/api/docs` against Ktor 3.5 + `@Resource` + kotlinx.serialization, with request/response **schemas derived from the `@Serializable` DTOs**.
- **Approach & fallback (Never Stranded):** evaluate a code-first library (candidates: tegral-openapi; Papsign/ktor-openapi-tools; others). **Decision gate:** if none is solid against Ktor 3.5, fall back to a **hybrid we own** — derive DTO JSON-Schemas from kotlinx.serialization and attach a minimal per-route operation description in the routing DSL. Either path yields code-derived docs that can't drift.
- **The spike's outcome sets the per-route pattern** used for every REST endpoint from here on: each route ships with its operation metadata in code, so the spec grows automatically and parity work is self-documenting.
- **Drift-guard test (kept regardless):** a Kotest test asserts every auth path in §1.1 appears in the generated spec and round-trips against the running app.

## 6. Testing (TDD — Kotest `FunSpec` + `testApplication`)
Because `:web` and the REST routes run in one Ktor app, these are **true end-to-end** (web route → real auth REST → real in-memory SQLite):
- Login success sets cookie + redirects; bad credentials re-render with the typed error.
- First-run setup creates root and lands authenticated.
- Register → `PendingApproval` routes to `/pending`; `Authenticated` lands home.
- Logout clears the session and cookie.
- CSRF: mutating request without a valid token is rejected.
- Transparent refresh: expired access token triggers one loopback refresh; rotated token persisted; **single-flight under concurrent requests** (no family-revoke).
- `requireWebSession` redirects to `/login` when session missing/unrecoverable.
- Active sessions: the list reflects the current session; revoking a session removes it.
- OpenAPI generation + drift-guard test (§5): every auth path appears in the generated spec and round-trips.
- Contract round-trip for any DTO touched (most already covered).

## 7. Phase 1 Boundary
- **In:** the OpenAPI code-first generation spike (**first task**, §5); the `:web` module + plumbing; loopback client; browser session model; login / setup / register / pending / logout; **active-sessions screen** (list/revoke devices); CSRF (`ktor-server-csrf`); Tailwind; static assets; app-shell skeleton; Swagger UI + generated auth docs + drift-guard test; full test suite above.
- **Deferred:** invite-code *claiming* (REST surface unconfirmed — revisit in Phase 6/admin or as a fast-follow), and all post-login content (home/library = Phase 2; Phase 1 lands on a placeholder).

## 8. Key File References
- Contract: `client/contract/.../api/AuthService.kt`, `.../api/resources/AuthResources.kt`, `.../api/dto/auth/*.kt`, `.../api/InstanceService.kt`, `.../api/dto/ServerInfo.kt`, `.../api/error/AuthError.kt`.
- Server: `client/server/.../routes/AuthRoutes.kt`, `.../routes/RegistrationStatusRoutes.kt`, `.../auth/JwtConfiguration.kt`, `.../auth/SessionService.kt`, `.../plugins/JwtAuth.kt`, `.../Application.kt`.
- Build: `client/settings.gradle.kts`, `client/gradle/libs.versions.toml`, `client/server/build.gradle.kts`, `client/build-logic/` (convention plugins).

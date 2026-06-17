# Web Client — Phase 1B: Browser Session, CSRF & Auth Screens — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the static `:web` shell from Phase 1A into a working authentication gate — a loopback REST client, a server-side browser-session store with single-flight token refresh, CSRF protection, and the login / first-run-setup / register / pending-approval / logout / active-sessions screens — all dogfooding the server's own REST API over loopback HTTP.

**Architecture:** `:web` (depends on `:contract` only) renders HTML with `kotlinx.html` + Tailwind + htmx and reaches the domain *exclusively* through the server's REST API over loopback HTTP. The browser holds an opaque session-id cookie; the server keeps a per-cookie `WebSession` in RAM (refresh token + cached access token). Because refresh tokens rotate on use, a per-session `Mutex` single-flights refresh so concurrent browser requests can't trigger replay/family-revoke. CSRF is a double-submit token enforced by the first-party `ktor-server-csrf` plugin, scoped per mutating route. The loopback client is injected through a seam so flow tests run fast in-memory with a fake, while a few real-port `embeddedServer` tests prove the genuine REST round-trip.

**Tech Stack:** Kotlin (JVM 21), Ktor 3.5.0 (CIO server + CIO client), `ktor-server-csrf`, `kotlinx.html` + `ktor-server-html-builder`, htmx 2.x (vendored) + htmx SSE extension (vendored), Tailwind (Phase 1A toolchain), kotlinx.serialization (`contractJson`), Kotest 6.1.11 `FunSpec` + `ktor-server-test-host` + `ktor-client-mock` + `kotlinx-coroutines-test`, Konsist.

**Worktree:** `/home/simonh/Code/lu-web` (branch `feat/web-client`). All paths below are relative to this worktree root unless absolute. Run all `git`/`gradle` commands from the worktree root. **Never merge to `main` until the whole web client is complete.**

**Spec:** `docs/superpowers/specs/2026-06-17-web-client-phase-1-auth-design.md` (§2.3 loopback client, §3 session model, §3.3 CSRF, §4 screens, §6 testing) and `…-web-client-architecture-design.md` (§3 BFF boundary). Phase 1A delivered §2.1/§2.2/§2.4/§5 (module, OpenAPI, Tailwind, shell); this plan is the rest of Phase 1.

**Verified facts this plan is built on (do not re-derive):**
- **AppResult wire shape** is a polymorphic envelope with a `type` discriminator: `{"type":"Success","data":…}` / `{"type":"Failure","error":{…}}`. Auth REST endpoints (`/login /register /setup /refresh /logout /logout/all /current-user /sessions /sessions/{id}`) **all** return `AppResult<T>` on both success and failure, so `.body<AppResult<T>>()` decodes either branch.
- **`GET /api/v1/instance` returns a *bare* `ServerInfo`** (not wrapped), and a *bare* `AppError` on failure. `GET /api/v1/auth/registration-status/{userId}` returns a *bare* `RegistrationStatusEvent`. These two need status-based decoding, not the envelope.
- **Bearer auth** provider is `JWT_PROVIDER = "jwt"`; clients send `Authorization: Bearer <accessToken.value>`. Access TTL 15 min; refresh tokens rotate on use (replay → family revoke).
- **Canonical JSON** is `com.calypsan.listenup.api.contractJson` (`ignoreUnknownKeys`, `isLenient`). Use it everywhere a `Json` is configured.
- **`ktor-server-csrf:3.5.0` exists** (`io.ktor.server.plugins.csrf`, plugin object `CSRF`, config `CSRFConfig`). The plugin **skips GET/HEAD/OPTIONS** and **fails closed** when a checked header is absent. `checkHeader(name) { … }` exposes `request` in its lambda; `onFailure { … }` exposes `call`.
- **Test bootstrap for an authed user:** `POST /api/v1/auth/setup` (first/root user) → `POST /api/v1/auth/register` (default test policy is `OPEN`, returns `RegisterResult.Authenticated`).
- **Real-port harness** pattern lives at `server/src/test/kotlin/com/calypsan/listenup/server/e2e/AuthEndToEndFixture.kt` (`applicationEnvironment { config = MapApplicationConfig(...) }` + `embeddedServer(CIO, environment, configure = { connectors.add(EngineConnectorBuilder()…) })`, `server.engine.resolvedConnectors()`).

**Exact contract types (from `:contract`, package roots noted):**
- `com.calypsan.listenup.api.dto.auth`: `LoginRequest(email, password, sessionLabel?=null, deviceInfo?=null, timezone="UTC")`; `RegisterRequest(email, password, displayName, sessionLabel?=null, deviceInfo?=null)`; `RefreshRequest(refreshToken: RefreshToken)`; `AuthSession(accessToken: AccessToken, accessTokenExpiresAt: Long, refreshToken: RefreshToken, refreshTokenExpiresAt: Long, sessionId: SessionId, user: User)`; `RegisterResult` sealed = `Authenticated(session: AuthSession)` | `PendingApproval(userId: UserId)`; `SessionSummary(id: SessionId, label: String?, deviceInfo?, userAgent?, createdAt: Long, lastUsedAt: Long, current: Boolean)`; `User(id: UserId, email, displayName, role: UserRole, status: UserStatus, createdAt: Long, permissions, approvedBy?, approvedAt?)`; `UserRole{ROOT,ADMIN,MEMBER}`; `UserStatus{ACTIVE,PENDING_APPROVAL,DENIED}`; `RegistrationPolicy{OPEN,APPROVAL_QUEUE,CLOSED}`; `RegistrationStatusEvent(status: String, timestamp: String?=null, message: String?=null)` with `status ∈ {"pending","approved","denied"}`; value classes `UserId/SessionId/AccessToken/RefreshToken`, each `@JvmInline value class X(val value: String)`.
- `com.calypsan.listenup.api.dto`: `ServerInfo(name, version, apiVersion, setupRequired: Boolean, registrationPolicy: RegistrationPolicy, remoteUrl?, instanceId)`.
- `com.calypsan.listenup.api.result`: `AppResult<out T>` = `Success<T>(data)` | `Failure(error: AppError)`.
- `com.calypsan.listenup.api.error`: `AppError` (interface; `message/code/correlationId/isRetryable/debugInfo`), top-level `InternalError(correlationId?=null, debugInfo?=null, cause?=null)`, and `AuthError.*` subtypes.

**Conventions to mirror (verified in-repo):**
- Tests: Kotest `FunSpec`; `io.ktor.server.testing.testApplication { … application { module() } }` for full-app; `createClient { install(ContentNegotiation){ json(contractJson) } }` whenever a body is deserialized.
- Konsist rules: `Konsist.scopeFromProduction().files.filter { it.path.contains("/web/src/main/") }` + `io.kotest.matchers.collections.shouldBeEmpty`.
- Module build uses `id("listenup.jvm")`, `freeCompilerArgs.add("-Xskip-prerelease-check")`, `tasks.test { useJUnitPlatform() }`.

---

## File Structure

**Created (`:web` main):**
- `web/src/main/kotlin/com/calypsan/listenup/web/loopback/LoopbackAuthClient.kt` — interface: the BFF's view of the auth REST surface, typed with `:contract` DTOs.
- `web/src/main/kotlin/com/calypsan/listenup/web/loopback/KtorLoopbackAuthClient.kt` — Ktor-client impl over an injected `HttpClient`.
- `web/src/main/kotlin/com/calypsan/listenup/web/session/WebSession.kt` — per-browser RAM session (mutable token state + per-session `Mutex`).
- `web/src/main/kotlin/com/calypsan/listenup/web/session/WebSessionStore.kt` — `cookieId → WebSession` map + opaque-id minting.
- `web/src/main/kotlin/com/calypsan/listenup/web/session/WebSessionAuthenticator.kt` — single-flight access-token freshness.
- `web/src/main/kotlin/com/calypsan/listenup/web/session/WebSessions.kt` — cookie names + set/read/expire helpers + `requireWebSession`.
- `web/src/main/kotlin/com/calypsan/listenup/web/security/Csrf.kt` — CSRF constants, the `CSRFConfig` lambda, token minting + `lu_csrf` cookie helper.
- `web/src/main/kotlin/com/calypsan/listenup/web/html/AuthPages.kt` — page + form builders (login/setup/register/pending/sessions).
- `web/src/main/kotlin/com/calypsan/listenup/web/routes/EntryRoutes.kt` — `GET /` entry routing.
- `web/src/main/kotlin/com/calypsan/listenup/web/routes/LoginRoutes.kt` — login + first-run setup.
- `web/src/main/kotlin/com/calypsan/listenup/web/routes/RegisterRoutes.kt` — register + pending + pending-status poll.
- `web/src/main/kotlin/com/calypsan/listenup/web/routes/AccountRoutes.kt` — logout + active sessions.
- `web/src/main/resources/web/htmx-ext-sse.js` — vendored htmx SSE extension (static asset).

**Created (`:web` test):**
- `web/src/test/kotlin/com/calypsan/listenup/web/testing/FakeLoopbackAuthClient.kt` — canned-response fake.
- `web/src/test/kotlin/com/calypsan/listenup/web/testing/WebTestApp.kt` — in-memory harness (mount web routes with a fake + a CSRF-aware client).
- `web/src/test/kotlin/com/calypsan/listenup/web/loopback/KtorLoopbackAuthClientTest.kt`
- `web/src/test/kotlin/com/calypsan/listenup/web/session/WebSessionStoreTest.kt`
- `web/src/test/kotlin/com/calypsan/listenup/web/session/WebSessionAuthenticatorTest.kt`
- `web/src/test/kotlin/com/calypsan/listenup/web/security/CsrfRoutesTest.kt`
- `web/src/test/kotlin/com/calypsan/listenup/web/routes/LoginRoutesTest.kt`
- `web/src/test/kotlin/com/calypsan/listenup/web/routes/SetupRoutesTest.kt`
- `web/src/test/kotlin/com/calypsan/listenup/web/routes/RegisterRoutesTest.kt`
- `web/src/test/kotlin/com/calypsan/listenup/web/routes/AccountRoutesTest.kt`
- `web/src/test/kotlin/com/calypsan/listenup/web/html/AppShellTest.kt`

**Created (`:server` test):**
- `server/src/test/kotlin/com/calypsan/listenup/server/e2e/WebAuthEndToEndTest.kt` — real-port loopback round-trip smoke.

**Modified:**
- `gradle/libs.versions.toml` — add `ktor-server-csrf` alias.
- `web/build.gradle.kts` — add `ktor-server-csrf` (main) + test deps (`ktor-server-test-host`, `ktor-client-mock`, `kotlinx-coroutines-test`, `ktor-client-content-negotiation`).
- `web/src/main/kotlin/com/calypsan/listenup/web/WebUi.kt` — two `installWebUi` overloads + `WebDependencies`.
- `web/src/main/kotlin/com/calypsan/listenup/web/html/AppShell.kt` — optional `csrfToken` head slot.
- `web/src/main/kotlin/com/calypsan/listenup/web/routes/ShellRoutes.kt` — **deleted** (its `GET /` moves to `EntryRoutes.kt`).
- `web/src/test/kotlin/com/calypsan/listenup/web/konsist/WebBoundaryKonsistTest.kt` — add non-vacuity assertion (1A backlog item).
- `server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt` — keep only asset-serving assertions (the `GET /` assertion moves to the real-port e2e, since `/` now requires loopback).

---

## Task 1: Loopback REST client

The BFF's typed view of the auth REST surface. Pure unit-tested with `ktor-client-mock` — no server.

**Files:**
- Modify: `web/build.gradle.kts`
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/loopback/LoopbackAuthClient.kt`
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/loopback/KtorLoopbackAuthClient.kt`
- Test: `web/src/test/kotlin/com/calypsan/listenup/web/loopback/KtorLoopbackAuthClientTest.kt`

- [ ] **Step 1: Add test dependencies to `web/build.gradle.kts`**

In the `dependencies { }` block, the `// Test` section currently has kotest + konsist. Add these test deps (all aliases already exist in the catalog):

```kotlin
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 2: Write the failing test**

Create `web/src/test/kotlin/com/calypsan/listenup/web/loopback/KtorLoopbackAuthClientTest.kt`:

```kotlin
package com.calypsan.listenup.web.loopback

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json

class KtorLoopbackAuthClientTest :
    FunSpec({
        fun clientReturning(status: HttpStatusCode, body: String): KtorLoopbackAuthClient {
            val engine =
                MockEngine {
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val http =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(contractJson) }
                }
            return KtorLoopbackAuthClient(http)
        }

        test("login decodes a Success envelope into AppResult.Success") {
            val authSessionJson =
                """
                {"type":"Success","data":{"accessToken":"jwt","accessTokenExpiresAt":1,
                "refreshToken":"rt","refreshTokenExpiresAt":2,"sessionId":"sid",
                "user":{"id":"uid","email":"a@x","displayName":"A","role":"MEMBER",
                "status":"ACTIVE","createdAt":0}}}
                """.trimIndent()
            val client = clientReturning(HttpStatusCode.OK, authSessionJson)

            val result = client.login(LoginRequest("a@x", "password1"))

            val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
            (success.data as com.calypsan.listenup.api.dto.auth.AuthSession).accessToken shouldBe AccessToken("jwt")
        }

        test("login decodes a Failure envelope into the typed AuthError") {
            val failureJson =
                """{"type":"Failure","error":{"type":"AuthError.InvalidCredentials","correlationId":"c1"}}"""
            val client = clientReturning(HttpStatusCode.Unauthorized, failureJson)

            val result = client.login(LoginRequest("a@x", "password1"))

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AuthError.InvalidCredentials>()
        }

        test("serverInfo decodes a bare ServerInfo body (not an envelope)") {
            val infoJson =
                """{"name":"ListenUp","version":"0.0.1","apiVersion":"v1","setupRequired":true,
                "registrationPolicy":"OPEN","instanceId":"id1"}""".trimIndent()
            val client = clientReturning(HttpStatusCode.OK, infoJson)

            val result = client.serverInfo()

            val success = result.shouldBeInstanceOf<AppResult.Success<ServerInfo>>()
            success.data.setupRequired shouldBe true
            success.data.registrationPolicy shouldBe RegistrationPolicy.OPEN
        }
    })
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.loopback.KtorLoopbackAuthClientTest"`
Expected: FAIL — `LoopbackAuthClient`/`KtorLoopbackAuthClient` unresolved.

- [ ] **Step 4: Create the interface** `web/src/main/kotlin/com/calypsan/listenup/web/loopback/LoopbackAuthClient.kt`

```kotlin
package com.calypsan.listenup.web.loopback

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult

/**
 * The BFF's typed view of the server's auth REST surface, reached over loopback HTTP.
 * `:web` depends only on `:contract`; this interface is the single seam through which
 * web routes touch the domain. Injected so flow tests can supply a fake.
 */
interface LoopbackAuthClient {
    /** `GET /api/v1/instance` — bare [ServerInfo] body. */
    suspend fun serverInfo(): AppResult<ServerInfo>

    suspend fun login(request: LoginRequest): AppResult<AuthSession>

    suspend fun setup(request: RegisterRequest): AppResult<AuthSession>

    suspend fun register(request: RegisterRequest): AppResult<RegisterResult>

    suspend fun refresh(request: RefreshRequest): AppResult<AuthSession>

    suspend fun logout(accessToken: AccessToken): AppResult<Unit>

    suspend fun listSessions(accessToken: AccessToken): AppResult<List<SessionSummary>>

    suspend fun revokeSession(accessToken: AccessToken, id: SessionId): AppResult<Unit>

    /** `GET /api/v1/auth/registration-status/{userId}` — bare [RegistrationStatusEvent] body. */
    suspend fun registrationStatus(userId: UserId): AppResult<RegistrationStatusEvent>
}
```

- [ ] **Step 5: Create the impl** `web/src/main/kotlin/com/calypsan/listenup/web/loopback/KtorLoopbackAuthClient.kt`

```kotlin
package com.calypsan.listenup.web.loopback

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * Loopback implementation. The [client] is configured with a default base URL and
 * [com.calypsan.listenup.api.contractJson] content negotiation by [installWebUi]
 * (production) or supplied directly (tests).
 *
 * Auth endpoints always return an `AppResult<T>` envelope (success *and* failure), so
 * [envelope] decodes either branch. `/instance` and `/registration-status` return bare
 * bodies, so [bare] switches on the HTTP status. Loopback transport failures (effectively
 * impossible in-process) fold to [InternalError].
 */
class KtorLoopbackAuthClient(
    private val client: HttpClient,
) : LoopbackAuthClient {
    override suspend fun serverInfo(): AppResult<ServerInfo> =
        bare { client.get("/api/v1/instance") }

    override suspend fun login(request: LoginRequest): AppResult<AuthSession> =
        envelope { client.post("/api/v1/auth/login") { jsonBody(request) } }

    override suspend fun setup(request: RegisterRequest): AppResult<AuthSession> =
        envelope { client.post("/api/v1/auth/setup") { jsonBody(request) } }

    override suspend fun register(request: RegisterRequest): AppResult<RegisterResult> =
        envelope { client.post("/api/v1/auth/register") { jsonBody(request) } }

    override suspend fun refresh(request: RefreshRequest): AppResult<AuthSession> =
        envelope { client.post("/api/v1/auth/refresh") { jsonBody(request) } }

    override suspend fun logout(accessToken: AccessToken): AppResult<Unit> =
        envelope { client.post("/api/v1/auth/logout") { bearerAuth(accessToken.value) } }

    override suspend fun listSessions(accessToken: AccessToken): AppResult<List<SessionSummary>> =
        envelope { client.get("/api/v1/auth/sessions") { bearerAuth(accessToken.value) } }

    override suspend fun revokeSession(accessToken: AccessToken, id: SessionId): AppResult<Unit> =
        envelope { client.delete("/api/v1/auth/sessions/${id.value}") { bearerAuth(accessToken.value) } }

    override suspend fun registrationStatus(userId: UserId): AppResult<RegistrationStatusEvent> =
        bare { client.get("/api/v1/auth/registration-status/${userId.value}") }

    private suspend inline fun <reified T> envelope(call: () -> HttpResponse): AppResult<T> =
        runLoopback { call().body<AppResult<T>>() }

    private suspend inline fun <reified T> bare(call: () -> HttpResponse): AppResult<T> =
        runLoopback {
            val response = call()
            if (response.status.isSuccess()) {
                AppResult.Success(response.body<T>())
            } else {
                AppResult.Failure(response.body<AppError>())
            }
        }

    private inline fun <T> runLoopback(block: () -> AppResult<T>): AppResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(InternalError(debugInfo = e.message))
        }
}

private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: Any) {
    contentType(ContentType.Application.Json)
    setBody(body)
}
```

> NOTE: `runLoopback` is `inline` so the `reified T` of its callers survives. `catch (CancellationException)` is re-thrown per the structured-concurrency rule in CLAUDE.md.

- [ ] **Step 6: Run the test to verify it passes**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.loopback.KtorLoopbackAuthClientTest"`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add web/build.gradle.kts web/src/main/kotlin/com/calypsan/listenup/web/loopback web/src/test/kotlin/com/calypsan/listenup/web/loopback
git commit -m "🧱 feat(web): loopback REST client over :contract DTOs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Browser session store + single-flight refresh

The crux: a server-side `cookieId → WebSession` map, and a per-session-`Mutex` authenticator that single-flights refresh so rotation can't trigger family-revoke. The single-flight invariant is tested where it lives (a `:web` unit test with a fake clock + counting fake).

**Files:**
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/session/WebSession.kt`
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/session/WebSessionStore.kt`
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/session/WebSessionAuthenticator.kt`
- Test: `web/src/test/kotlin/com/calypsan/listenup/web/session/WebSessionStoreTest.kt`
- Test: `web/src/test/kotlin/com/calypsan/listenup/web/session/WebSessionAuthenticatorTest.kt`

- [ ] **Step 1: Write the failing store test** `web/src/test/kotlin/com/calypsan/listenup/web/session/WebSessionStoreTest.kt`

```kotlin
package com.calypsan.listenup.web.session

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class WebSessionStoreTest :
    FunSpec({
        fun sampleSession() =
            WebSession(
                sessionId = SessionId("s1"),
                userId = UserId("u1"),
                role = UserRole.MEMBER,
                accessToken = AccessToken("at"),
                refreshToken = RefreshToken("rt"),
                accessExpiresAt = 1_000L,
            )

        test("put then get returns the stored session") {
            val store = WebSessionStore()
            val id = store.newCookieId()
            val session = sampleSession()
            store.put(id, session)
            store.get(id) shouldBe session
        }

        test("remove deletes the session") {
            val store = WebSessionStore()
            val id = store.newCookieId()
            store.put(id, sampleSession())
            store.remove(id)
            store.get(id).shouldBeNull()
        }

        test("newCookieId mints distinct, non-trivial opaque ids") {
            val store = WebSessionStore()
            val a = store.newCookieId()
            val b = store.newCookieId()
            a shouldNotBe b
            (a.length >= 32) shouldBe true
        }
    })
```

- [ ] **Step 2: Run to verify it fails**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.session.WebSessionStoreTest"`
Expected: FAIL — `WebSession`/`WebSessionStore` unresolved.

- [ ] **Step 3: Create `WebSession.kt`**

```kotlin
package com.calypsan.listenup.web.session

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import kotlinx.coroutines.sync.Mutex

/**
 * One browser's server-side session, keyed by an opaque cookie id. Holds the rotating
 * refresh token and the cached access token in RAM only (never persisted). [refreshMutex]
 * single-flights token refresh so concurrent requests on the same browser session can't
 * present the same (rotating) refresh token twice and trip replay/family-revoke.
 */
class WebSession(
    val sessionId: SessionId,
    val userId: UserId,
    val role: UserRole,
    @Volatile var accessToken: AccessToken,
    @Volatile var refreshToken: RefreshToken,
    @Volatile var accessExpiresAt: Long,
) {
    val refreshMutex: Mutex = Mutex()
}
```

- [ ] **Step 4: Create `WebSessionStore.kt`**

```kotlin
package com.calypsan.listenup.web.session

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory `cookieId → WebSession` registry. Cleared on restart (web users re-login —
 * accepted per the self-host threat model). Cookie ids are 256-bit, URL-safe, unguessable.
 */
class WebSessionStore {
    private val sessions = ConcurrentHashMap<String, WebSession>()
    private val random = SecureRandom()

    fun get(cookieId: String): WebSession? = sessions[cookieId]

    fun put(cookieId: String, session: WebSession) {
        sessions[cookieId] = session
    }

    fun remove(cookieId: String): WebSession? = sessions.remove(cookieId)

    fun newCookieId(): String {
        val bytes = ByteArray(COOKIE_ID_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val COOKIE_ID_BYTES = 32
    }
}
```

- [ ] **Step 5: Run the store test to verify it passes**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.session.WebSessionStoreTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Write the failing single-flight test** `web/src/test/kotlin/com/calypsan/listenup/web/session/WebSessionAuthenticatorTest.kt`

```kotlin
package com.calypsan.listenup.web.session

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.testing.FakeLoopbackAuthClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class WebSessionAuthenticatorTest :
    FunSpec({
        fun rotatedSession(suffix: String) =
            AuthSession(
                accessToken = AccessToken("access-$suffix"),
                accessTokenExpiresAt = 10_000L,
                refreshToken = RefreshToken("refresh-$suffix"),
                refreshTokenExpiresAt = 99_000L,
                sessionId = SessionId("s1"),
                user =
                    User(
                        id = UserId("u1"),
                        email = "a@x",
                        displayName = "A",
                        role = UserRole.MEMBER,
                        status = UserStatus.ACTIVE,
                        createdAt = 0L,
                    ),
            )

        fun expiredSession() =
            WebSession(
                sessionId = SessionId("s1"),
                userId = UserId("u1"),
                role = UserRole.MEMBER,
                accessToken = AccessToken("stale"),
                refreshToken = RefreshToken("rt0"),
                accessExpiresAt = 0L, // already expired against the test clock below
            )

        test("a fresh access token is returned without refreshing") {
            val loopback = FakeLoopbackAuthClient()
            val authenticator = WebSessionAuthenticator(loopback, clock = { 1_000L })
            val session = expiredSession().apply { accessExpiresAt = 1_000_000L }

            authenticator.freshAccessToken(session) shouldBe AccessToken("stale")
            loopback.refreshCalls shouldBe 0
        }

        test("concurrent requests on an expired session trigger exactly one refresh") {
            val loopback =
                FakeLoopbackAuthClient().apply {
                    refreshResult = AppResult.Success(rotatedSession("v1"))
                    refreshDelayMs = 50L // force the callers to overlap inside the mutex
                }
            val authenticator = WebSessionAuthenticator(loopback, clock = { 1_000L })
            val session = expiredSession()

            val tokens =
                runTest {
                    val deferred = (1..8).map { async { authenticator.freshAccessToken(session) } }
                    deferred.awaitAll()
                }

            loopback.refreshCalls shouldBe 1
            tokens.forEach { it shouldBe AccessToken("access-v1") }
            session.refreshToken shouldBe RefreshToken("refresh-v1")
        }

        test("a failed refresh yields null and leaves the stored token unrotated") {
            val loopback =
                FakeLoopbackAuthClient().apply {
                    refreshResult = AppResult.Failure(AuthError.InvalidRefreshToken(familyRevoked = true))
                }
            val authenticator = WebSessionAuthenticator(loopback, clock = { 1_000L })
            val session = expiredSession()

            runTest { authenticator.freshAccessToken(session).shouldBeNull() }
            session.refreshToken shouldBe RefreshToken("rt0")
        }
    })
```

> NOTE: `FakeLoopbackAuthClient` is created in Step 8 (Task 3 reuses it for flow tests; defining it now keeps this test self-contained). `delay` runs on `runTest`'s virtual clock, so the 50ms is instant wall-time but still forces coroutine interleaving inside the mutex.

- [ ] **Step 7: Run to verify it fails**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.session.WebSessionAuthenticatorTest"`
Expected: FAIL — `WebSessionAuthenticator` and `FakeLoopbackAuthClient` unresolved.

- [ ] **Step 8: Create the fake** `web/src/test/kotlin/com/calypsan/listenup/web/testing/FakeLoopbackAuthClient.kt`

```kotlin
package com.calypsan.listenup.web.testing

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.loopback.LoopbackAuthClient
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Canned-response [LoopbackAuthClient] for fast in-memory web-route tests. Each method
 * returns its mutable `*Result`; refresh additionally counts calls and can [delay] to
 * force concurrency in the single-flight test.
 */
class FakeLoopbackAuthClient : LoopbackAuthClient {
    var serverInfoResult: AppResult<ServerInfo> =
        AppResult.Success(
            ServerInfo(
                name = "ListenUp",
                version = "0.0.1",
                apiVersion = "v1",
                setupRequired = false,
                registrationPolicy = RegistrationPolicy.OPEN,
                instanceId = "test-instance",
            ),
        )
    var loginResult: AppResult<AuthSession> = AppResult.Failure(InternalError(debugInfo = "unset"))
    var setupResult: AppResult<AuthSession> = AppResult.Failure(InternalError(debugInfo = "unset"))
    var registerResult: AppResult<RegisterResult> = AppResult.Failure(InternalError(debugInfo = "unset"))
    var refreshResult: AppResult<AuthSession> = AppResult.Failure(InternalError(debugInfo = "unset"))
    var logoutResult: AppResult<Unit> = AppResult.Success(Unit)
    var listSessionsResult: AppResult<List<SessionSummary>> = AppResult.Success(emptyList())
    var revokeResult: AppResult<Unit> = AppResult.Success(Unit)
    var registrationStatusResult: AppResult<RegistrationStatusEvent> =
        AppResult.Success(RegistrationStatusEvent(status = "pending"))

    var refreshDelayMs: Long = 0L
    private val refreshCounter = AtomicInteger(0)
    val refreshCalls: Int get() = refreshCounter.get()

    override suspend fun serverInfo() = serverInfoResult

    override suspend fun login(request: LoginRequest) = loginResult

    override suspend fun setup(request: RegisterRequest) = setupResult

    override suspend fun register(request: RegisterRequest) = registerResult

    override suspend fun refresh(request: RefreshRequest): AppResult<AuthSession> {
        refreshCounter.incrementAndGet()
        if (refreshDelayMs > 0) delay(refreshDelayMs)
        return refreshResult
    }

    override suspend fun logout(accessToken: AccessToken) = logoutResult

    override suspend fun listSessions(accessToken: AccessToken) = listSessionsResult

    override suspend fun revokeSession(accessToken: AccessToken, id: SessionId) = revokeResult

    override suspend fun registrationStatus(userId: UserId) = registrationStatusResult
}
```

- [ ] **Step 9: Create `WebSessionAuthenticator.kt`**

```kotlin
package com.calypsan.listenup.web.session

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.loopback.LoopbackAuthClient
import kotlinx.coroutines.sync.withLock

/**
 * Returns a non-expired access token for a [WebSession], refreshing through the loopback
 * client when needed. Refresh is **single-flighted** per session via [WebSession.refreshMutex]:
 * the first caller rotates the token while the rest wait, then everyone re-checks and reuses
 * the freshly-rotated token. This prevents two concurrent requests from presenting the same
 * (rotating) refresh token and tripping the server's replay/family-revoke.
 *
 * @param clock injectable time source (millis) so the freshness boundary is testable.
 * @param skewMs treat a token as expired this long before its real expiry.
 */
class WebSessionAuthenticator(
    private val loopback: LoopbackAuthClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val skewMs: Long = DEFAULT_SKEW_MS,
) {
    /** A valid access token, or `null` when refresh fails (caller should redirect to /login). */
    suspend fun freshAccessToken(session: WebSession): AccessToken? {
        if (!isExpired(session)) return session.accessToken
        return session.refreshMutex.withLock {
            if (!isExpired(session)) {
                session.accessToken
            } else {
                when (val result = loopback.refresh(RefreshRequest(session.refreshToken))) {
                    is AppResult.Success -> {
                        val rotated = result.data
                        session.accessToken = rotated.accessToken
                        session.refreshToken = rotated.refreshToken
                        session.accessExpiresAt = rotated.accessTokenExpiresAt
                        rotated.accessToken
                    }
                    is AppResult.Failure -> null
                }
            }
        }
    }

    private fun isExpired(session: WebSession): Boolean = clock() >= session.accessExpiresAt - skewMs

    private companion object {
        const val DEFAULT_SKEW_MS = 30_000L
    }
}
```

- [ ] **Step 10: Run both session tests to verify they pass**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.session.*"`
Expected: PASS (store: 3, authenticator: 3).

- [ ] **Step 11: Commit**

```bash
git add web/src/main/kotlin/com/calypsan/listenup/web/session web/src/test/kotlin/com/calypsan/listenup/web/session web/src/test/kotlin/com/calypsan/listenup/web/testing/FakeLoopbackAuthClient.kt
git commit -m "🧱 feat(web): in-memory web-session store + single-flight refresh

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: CSRF, session cookies, app-shell head slot & the injection seam

Wire the `installWebUi` injection seam, the cookie helpers + `requireWebSession`, the CSRF double-submit machinery, and the app-shell CSRF `<meta>` slot. Prove CSRF behavior with a minimal in-memory app.

**Design note (CSRF):** A same-origin BFF is protected by a **double-submit token**: every rendered page sets an HttpOnly `lu_csrf` cookie and embeds the same token in a `<meta name="csrf-token">`; an htmx `configRequest` hook echoes it back as `X-CSRF-Token`; the `ktor-server-csrf` `checkHeader` compares header to cookie. The plugin already skips safe methods and fails closed on a missing header. Installed **per mutating route** (not app-wide) so the loopback/REST surface is untouched. `originMatchesHost()` is intentionally omitted (redundant for a same-origin double-submit design, and it would add Host/Origin matching fragility to in-memory tests); it is available later as reverse-proxy hardening.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `web/build.gradle.kts`
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/security/Csrf.kt`
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/session/WebSessions.kt`
- Modify: `web/src/main/kotlin/com/calypsan/listenup/web/html/AppShell.kt`
- Modify: `web/src/main/kotlin/com/calypsan/listenup/web/WebUi.kt`
- Delete: `web/src/main/kotlin/com/calypsan/listenup/web/routes/ShellRoutes.kt`
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/routes/EntryRoutes.kt` (placeholder this task; filled in Task 4)
- Create: `web/src/test/kotlin/com/calypsan/listenup/web/testing/WebTestApp.kt`
- Test: `web/src/test/kotlin/com/calypsan/listenup/web/security/CsrfRoutesTest.kt`
- Test: `web/src/test/kotlin/com/calypsan/listenup/web/html/AppShellTest.kt`

- [ ] **Step 1: Add the `ktor-server-csrf` catalog alias** in `gradle/libs.versions.toml`

In `[libraries]`, alongside the other `ktor-server-*` entries, add:

```toml
ktor-server-csrf = { module = "io.ktor:ktor-server-csrf", version.ref = "ktor" }
```

- [ ] **Step 2: Add the dependency to `web/build.gradle.kts`**

In the Ktor-server section of `dependencies { }` (next to `ktor.server.core`), add:

```kotlin
    implementation(libs.ktor.server.csrf)
```

- [ ] **Step 3: Write the failing app-shell test** `web/src/test/kotlin/com/calypsan/listenup/web/html/AppShellTest.kt`

```kotlin
package com.calypsan.listenup.web.html

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.html
import kotlinx.html.stream.createHTML

class AppShellTest :
    FunSpec({
        test("appShell renders the CSRF meta + htmx header hook when a token is supplied") {
            val html = createHTML().html { appShell(pageTitle = "X", csrfToken = "tok-123") { } }
            html shouldContain """name="csrf-token""""
            html shouldContain "tok-123"
            html shouldContain "htmx:configRequest"
            html shouldContain "X-CSRF-Token"
        }

        test("appShell omits the CSRF meta when no token is supplied") {
            val html = createHTML().html { appShell(pageTitle = "X", csrfToken = null) { } }
            html shouldNotContain "csrf-token"
        }
    })
```

- [ ] **Step 4: Run to verify it fails**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.html.AppShellTest"`
Expected: FAIL — `appShell` has no `csrfToken` parameter.

- [ ] **Step 5: Add the CSRF head slot to `AppShell.kt`**

Replace the contents of `web/src/main/kotlin/com/calypsan/listenup/web/html/AppShell.kt` with:

```kotlin
package com.calypsan.listenup.web.html

import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.body
import kotlinx.html.head
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * The base HTML document every web page renders into. Pages supply body content via
 * [content]; the shell provides the head (Tailwind stylesheet, htmx runtime) and, when a
 * [csrfToken] is supplied, the CSRF `<meta>` plus an htmx `configRequest` hook that echoes
 * the token back as the `X-CSRF-Token` header on every htmx request (the double-submit pair
 * checked by [com.calypsan.listenup.web.security.webCsrfConfig]).
 */
fun HTML.appShell(
    pageTitle: String = "ListenUp",
    csrfToken: String? = null,
    content: MAIN.() -> Unit,
) {
    lang = "en"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title { +pageTitle }
        link(rel = "stylesheet", href = "/assets/app.css")
        script(src = "/assets/htmx.min.js") {}
        if (csrfToken != null) {
            meta(name = "csrf-token", content = csrfToken)
            script { unsafe { +CSRF_HTMX_HOOK } }
        }
    }
    body {
        main(classes = "mx-auto") {
            content()
        }
    }
}

private const val CSRF_HTMX_HOOK =
    """
    document.body.addEventListener('htmx:configRequest', function (e) {
      var meta = document.querySelector('meta[name=csrf-token]');
      if (meta) { e.detail.headers['X-CSRF-Token'] = meta.content; }
    });
    """
```

- [ ] **Step 6: Run the app-shell test to verify it passes**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.html.AppShellTest"`
Expected: PASS (2 tests).

- [ ] **Step 7: Create `security/Csrf.kt`**

```kotlin
package com.calypsan.listenup.web.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.csrf.CSRFConfig
import io.ktor.server.response.respond
import java.security.SecureRandom
import java.util.Base64

/** Name of the double-submit CSRF cookie (HttpOnly; the page carries the same value in a meta tag). */
const val CSRF_COOKIE: String = "lu_csrf"

/** Header htmx echoes the token back in (see the app-shell `configRequest` hook). */
const val CSRF_HEADER: String = "X-CSRF-Token"

private val csrfRandom = SecureRandom()
private const val CSRF_TOKEN_BYTES = 32

/** Mint a fresh, unguessable CSRF token (URL-safe base64, no padding). */
fun newCsrfToken(): String {
    val bytes = ByteArray(CSRF_TOKEN_BYTES)
    csrfRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * The shared CSRF config for every mutating web route. Double-submit: the submitted
 * [CSRF_HEADER] must equal the [CSRF_COOKIE] value set when the page was rendered. The
 * plugin skips GET/HEAD/OPTIONS and fails closed when the header is absent.
 */
val webCsrfConfig: CSRFConfig.() -> Unit = {
    checkHeader(CSRF_HEADER) { token ->
        token.isNotEmpty() && token == request.cookies[CSRF_COOKIE]
    }
    onFailure { call.respond(HttpStatusCode.Forbidden) }
}
```

> NOTE: `checkHeader`'s lambda exposes `request`; `onFailure`'s lambda exposes `call`. If the Ktor 3.5 signatures differ slightly (e.g. `onFailure { respond(...) }` with a call receiver), adjust to whatever resolves — the behavior (compare header to cookie; 403 on failure) is fixed.

- [ ] **Step 8: Create `session/WebSessions.kt`** (cookie plumbing + `requireWebSession`)

```kotlin
package com.calypsan.listenup.web.session

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.web.security.CSRF_COOKIE
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondRedirect

/** Opaque browser-session cookie (HttpOnly, SameSite=Lax). */
const val SESSION_COOKIE: String = "lu_session"

/** What a protected handler gets after [requireWebSession] succeeds. */
data class WebSessionContext(
    val session: WebSession,
    val accessToken: AccessToken,
)

private fun ApplicationCall.isHttps(): Boolean = request.origin.scheme == "https"

/** Set the opaque session cookie (value = the store's cookie id). */
fun ApplicationCall.setSessionCookie(cookieId: String) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = cookieId,
            encoding = CookieEncoding.RAW,
            httpOnly = true,
            secure = isHttps(),
            path = "/",
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

/** Set the double-submit CSRF cookie (HttpOnly; value mirrored into the page's meta tag). */
fun ApplicationCall.setCsrfCookie(token: String) {
    response.cookies.append(
        Cookie(
            name = CSRF_COOKIE,
            value = token,
            encoding = CookieEncoding.RAW,
            httpOnly = true,
            secure = isHttps(),
            path = "/",
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}

fun ApplicationCall.expireSessionCookie() {
    response.cookies.appendExpired(SESSION_COOKIE, path = "/")
}

/**
 * Guard a protected web route. Loads the [WebSession] for the session cookie, ensures a
 * fresh access token (single-flight refresh), and returns the [WebSessionContext]. On any
 * failure it issues a redirect to `/login` and returns `null` — callers do `?: return@get`.
 */
suspend fun ApplicationCall.requireWebSession(
    store: WebSessionStore,
    authenticator: WebSessionAuthenticator,
): WebSessionContext? {
    val cookieId = request.cookies[SESSION_COOKIE]
    if (cookieId == null) {
        respondRedirect("/login")
        return null
    }
    val session = store.get(cookieId)
    if (session == null) {
        expireSessionCookie()
        respondRedirect("/login")
        return null
    }
    val accessToken = authenticator.freshAccessToken(session)
    if (accessToken == null) {
        store.remove(cookieId)
        expireSessionCookie()
        respondRedirect("/login")
        return null
    }
    return WebSessionContext(session, accessToken)
}
```

> NOTE: Ktor's `Cookie` carries `SameSite` via `extensions` (no first-class param). `appendExpired(name, path)` writes a Max-Age=0 cookie. Verify `request.origin.scheme` import path (`io.ktor.server.plugins.origin`); if unresolved, use `request.local.scheme`.

- [ ] **Step 9: Rework `WebUi.kt` into the injection seam**

Replace `web/src/main/kotlin/com/calypsan/listenup/web/WebUi.kt` with:

```kotlin
package com.calypsan.listenup.web

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.web.loopback.KtorLoopbackAuthClient
import com.calypsan.listenup.web.loopback.LoopbackAuthClient
import com.calypsan.listenup.web.routes.accountRoutes
import com.calypsan.listenup.web.routes.entryRoutes
import com.calypsan.listenup.web.routes.loginRoutes
import com.calypsan.listenup.web.routes.registerRoutes
import com.calypsan.listenup.web.session.WebSessionAuthenticator
import com.calypsan.listenup.web.session.WebSessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing

/**
 * Configuration handed to the embedded web UI by `:server` at mount time.
 *
 * @property loopbackBaseUrl Base URL the BFF uses to call the server's own REST API
 *   (e.g. "http://127.0.0.1:8080").
 */
data class WebUiConfig(
    val loopbackBaseUrl: String,
)

/** Collaborators the web routes need. Injected so tests can supply a fake loopback client. */
internal data class WebDependencies(
    val loopback: LoopbackAuthClient,
    val store: WebSessionStore,
    val authenticator: WebSessionAuthenticator,
)

/**
 * Production entry point: builds the real loopback HTTP client (CIO, pointed at
 * [WebUiConfig.loopbackBaseUrl], using [contractJson]) plus an in-memory session store, and
 * mounts the web UI. `:server` calls this from its routing setup.
 */
fun Application.installWebUi(config: WebUiConfig) {
    val httpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(contractJson) }
            defaultRequest { url(config.loopbackBaseUrl) }
        }
    val deps =
        WebDependencies(
            loopback = KtorLoopbackAuthClient(httpClient),
            store = WebSessionStore(),
            authenticator = WebSessionAuthenticator(KtorLoopbackAuthClient(httpClient)),
        )
    installWebUi(deps)
}

/** Internal seam: mount the routes against supplied [deps] (tests inject a fake loopback). */
internal fun Application.installWebUi(deps: WebDependencies) {
    routing {
        staticResources("/assets", "web")
        entryRoutes(deps)
        loginRoutes(deps)
        registerRoutes(deps)
        accountRoutes(deps)
    }
}
```

> NOTE: build one `KtorLoopbackAuthClient` and reuse it for both the `loopback` field and the authenticator (the duplication above is only to keep the snippet flat — prefer `val loopback = KtorLoopbackAuthClient(httpClient)` then pass it twice). `defaultRequest { url(...) }` import is `io.ktor.client.plugins.defaultRequest`.

- [ ] **Step 10: Delete the obsolete shell route and add an `EntryRoutes.kt` placeholder**

Delete `web/src/main/kotlin/com/calypsan/listenup/web/routes/ShellRoutes.kt`.

Create `web/src/main/kotlin/com/calypsan/listenup/web/routes/EntryRoutes.kt` (a temporary placeholder so the module compiles this task; Task 4 fills it and adds the sibling route files referenced by `WebUi.kt`). For this task, also create one-line empty placeholders for the three sibling functions so `installWebUi(deps)` resolves:

```kotlin
package com.calypsan.listenup.web.routes

import com.calypsan.listenup.web.WebDependencies
import io.ktor.server.routing.Route

/** Entry routing. Filled in Task 4. */
internal fun Route.entryRoutes(deps: WebDependencies) {
    // Task 4
}

/** Login + first-run setup. Filled in Task 4/5. */
internal fun Route.loginRoutes(deps: WebDependencies) {
    // Task 4 (login), Task 5 (setup)
}

/** Register + pending. Filled in Task 6. */
internal fun Route.registerRoutes(deps: WebDependencies) {
    // Task 6
}

/** Logout + active sessions. Filled in Task 7. */
internal fun Route.accountRoutes(deps: WebDependencies) {
    // Task 7
}
```

> These empty bodies are real, compilable scaffolding (not placeholders-for-omitted-content): each later task replaces exactly one of them. They live in one file now; Task 4 may split `registerRoutes`/`accountRoutes` into their own files (`RegisterRoutes.kt`, `AccountRoutes.kt`) — when you do, remove them from here.

- [ ] **Step 11: Create the in-memory test harness** `web/src/test/kotlin/com/calypsan/listenup/web/testing/WebTestApp.kt`

```kotlin
package com.calypsan.listenup.web.testing

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.installWebUi
import com.calypsan.listenup.web.security.CSRF_HEADER
import com.calypsan.listenup.web.session.WebSessionAuthenticator
import com.calypsan.listenup.web.session.WebSessionStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Mounts the web routes in an isolated in-memory app with the supplied [fake] loopback
 * client + a shared [store]. Tests get a cookie-aware, redirect-suppressing client so they
 * can assert on `Set-Cookie`, `Location`, and `HX-Redirect` directly.
 */
internal class WebTestContext(
    val fake: FakeLoopbackAuthClient,
    val store: WebSessionStore,
)

internal fun ApplicationTestBuilder.installTestWebUi(
    fake: FakeLoopbackAuthClient = FakeLoopbackAuthClient(),
    store: WebSessionStore = WebSessionStore(),
): WebTestContext {
    application {
        installWebUi(
            WebDependencies(
                loopback = fake,
                store = store,
                authenticator = WebSessionAuthenticator(fake) { 0L },
            ),
        )
    }
    return WebTestContext(fake, store)
}

internal fun ApplicationTestBuilder.webClient(): HttpClient =
    createClient {
        install(ContentNegotiation) { json(contractJson) }
        install(HttpCookies)
        followRedirects = false
    }

/** Attach the CSRF header for a mutating request, echoing whatever the cookie jar holds. */
internal fun HttpRequestBuilder.csrf(token: String) {
    header(CSRF_HEADER, token)
}
```

> NOTE: The authenticator clock is fixed at `0L` here, so tokens with any positive `accessExpiresAt` are considered fresh in flow tests (no refresh during simple flows). `followRedirects = false` lets tests assert on 302/`HX-Redirect`.

- [ ] **Step 12: Write the failing CSRF test** `web/src/test/kotlin/com/calypsan/listenup/web/security/CsrfRoutesTest.kt`

This test installs a tiny CSRF-protected route directly (the real screens come later) to pin CSRF behavior in isolation.

```kotlin
package com.calypsan.listenup.web.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.cookie
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class CsrfRoutesTest :
    FunSpec({
        fun ApplicationTestBuilderInit() = Unit // keep imports tidy; no-op marker

        test("a mutating request without the CSRF header is rejected (403)") {
            testApplication {
                application {
                    routing {
                        route("/guarded") {
                            install(CSRF, webCsrfConfig)
                            post { call.respondText("ok") }
                        }
                    }
                }
                val response = client.post("/guarded")
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("a mutating request with a matching header+cookie passes") {
            testApplication {
                application {
                    routing {
                        route("/guarded") {
                            install(CSRF, webCsrfConfig)
                            post { call.respondText("ok") }
                        }
                    }
                }
                val response =
                    client.post("/guarded") {
                        cookie(CSRF_COOKIE, "tok-1")
                        header(CSRF_HEADER, "tok-1")
                    }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("a mutating request whose header does not match the cookie is rejected") {
            testApplication {
                application {
                    routing {
                        route("/guarded") {
                            install(CSRF, webCsrfConfig)
                            post { call.respondText("ok") }
                        }
                    }
                }
                val response =
                    client.post("/guarded") {
                        cookie(CSRF_COOKIE, "tok-1")
                        header(CSRF_HEADER, "tok-2")
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })

import io.ktor.server.testing.ApplicationTestBuilder
```

> NOTE: Move the trailing `import` to the top of the file (kept inline here only to flag the extra import you need). Delete the `ApplicationTestBuilderInit` marker if your linter dislikes it — it exists only to make the missing-import obvious.

- [ ] **Step 13: Run the CSRF + shell tests**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.security.*" --tests "com.calypsan.listenup.web.html.*"`
Expected: PASS (CSRF: 3, shell: 2). The whole module must also still compile: `gradle :web:compileTestKotlin`.

- [ ] **Step 14: Commit**

```bash
git add gradle/libs.versions.toml web/build.gradle.kts web/src/main/kotlin/com/calypsan/listenup/web web/src/test/kotlin/com/calypsan/listenup/web
git rm web/src/main/kotlin/com/calypsan/listenup/web/routes/ShellRoutes.kt
git commit -m "🧱 feat(web): CSRF double-submit, session cookies + installWebUi seam

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Entry routing + Login

`GET /` decides where the browser lands; `/login` authenticates. This task fills `entryRoutes` and the login half of `loginRoutes`, and introduces the page builders.

**Files:**
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/html/AuthPages.kt`
- Modify: `web/src/main/kotlin/com/calypsan/listenup/web/routes/EntryRoutes.kt`
- Modify: `web/src/main/kotlin/com/calypsan/listenup/web/routes/LoginRoutes.kt` (extract `loginRoutes` from `EntryRoutes.kt` into its own file)
- Test: `web/src/test/kotlin/com/calypsan/listenup/web/routes/LoginRoutesTest.kt`
- Modify: `server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt`

- [ ] **Step 1: Write the failing login test** `web/src/test/kotlin/com/calypsan/listenup/web/routes/LoginRoutesTest.kt`

```kotlin
package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.testing.installTestWebUi
import com.calypsan.listenup.web.testing.webClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication

private fun authSession() =
    AuthSession(
        accessToken = AccessToken("at"),
        accessTokenExpiresAt = 9_999_999_999_999L,
        refreshToken = RefreshToken("rt"),
        refreshTokenExpiresAt = 9_999_999_999_999L,
        sessionId = SessionId("s1"),
        user =
            User(
                id = UserId("u1"),
                email = "a@x",
                displayName = "A",
                role = UserRole.MEMBER,
                status = UserStatus.ACTIVE,
                createdAt = 0L,
            ),
    )

class LoginRoutesTest :
    FunSpec({
        test("GET /login renders the form and sets a CSRF cookie") {
            testApplication {
                installTestWebUi()
                val client = webClient()

                val response = client.get("/login")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "name=\"email\""
                response.headers.values(HttpHeaders.SetCookie).any { it.startsWith("lu_csrf=") } shouldBe true
            }
        }

        test("POST /login with good credentials creates a session and HX-Redirects home") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.loginResult = AppResult.Success(authSession())
                val client = webClient()
                client.get("/login") // obtain the CSRF cookie
                val token = lastCsrfToken(client)

                val response =
                    client.submitForm(
                        url = "/login",
                        formParameters = parameters {
                            append("email", "a@x")
                            append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", token) }

                response.headers["HX-Redirect"] shouldBe "/"
                response.headers.values(HttpHeaders.SetCookie).any { it.startsWith("lu_session=") } shouldBe true
            }
        }

        test("POST /login with bad credentials re-renders the form with the typed error") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.loginResult = AppResult.Failure(AuthError.InvalidCredentials())
                val client = webClient()
                client.get("/login")
                val token = lastCsrfToken(client)

                val response =
                    client.submitForm(
                        url = "/login",
                        formParameters = parameters {
                            append("email", "a@x")
                            append("password", "wrong")
                        },
                    ) { header("X-CSRF-Token", token) }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Email or password did not match."
            }
        }
    })

/** Read the current `lu_csrf` value from the client's cookie jar via a helper request. */
private suspend fun lastCsrfToken(client: io.ktor.client.HttpClient): String {
    val cookies = client.cookies("http://localhost/")
    return cookies.first { it.name == "lu_csrf" }.value
}
```

> NOTE: `client.cookies(url)` comes from the `HttpCookies` plugin (`io.ktor.client.plugins.cookies.cookies`). htmx posts form-encoded data; `submitForm` matches that. The CSRF token must be read from the jar and sent as the header (the browser's htmx hook does this automatically; tests do it explicitly).

- [ ] **Step 2: Run to verify it fails**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.routes.LoginRoutesTest"`
Expected: FAIL — `/login` 404 / `entryRoutes` empty.

- [ ] **Step 3: Create the page builders** `web/src/main/kotlin/com/calypsan/listenup/web/html/AuthPages.kt`

```kotlin
package com.calypsan.listenup.web.html

import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.stream.createHTML

/** Render a full page through [appShell] with the CSRF token wired into the head. */
suspend fun ApplicationCall.respondPage(
    title: String,
    csrfToken: String?,
    block: kotlinx.html.MAIN.() -> Unit,
) = respondHtml { appShell(pageTitle = title, csrfToken = csrfToken) { block() } }

/** The login form fragment (id `auth-form`), reused for the full page and the error re-render. */
fun FlowContent.loginForm(email: String = "", error: String? = null) {
    div {
        id = "auth-form"
        h1 { +"Sign in" }
        if (error != null) {
            p(classes = "text-red-600") { +error }
        }
        form {
            attributes["hx-post"] = "/login"
            attributes["hx-target"] = "#auth-form"
            attributes["hx-swap"] = "outerHTML"
            label { +"Email" }
            input(type = InputType.email, name = "email") { value = email }
            label { +"Password" }
            input(type = InputType.password, name = "password")
            button(type = ButtonType.submit) { +"Sign in" }
        }
    }
}

/** Render just the login fragment to an HTML string (for htmx swap responses). */
fun loginFormFragment(email: String, error: String?): String =
    createHTML().div { loginForm(email, error) }.let { it } // div wrapper kept minimal
```

> NOTE: `loginFormFragment` should render *exactly* the `#auth-form` markup `hx-swap="outerHTML"` replaces. Implement it as `createHTML().run { ... }` emitting the same `div#auth-form` as `loginForm`. The simplest correct form: factor the inner markup so both the page and the fragment call the same `DIV.() -> Unit`. Keep the file under 300 lines.

- [ ] **Step 4: Fill `EntryRoutes.kt`** (replace the placeholder `entryRoutes`)

```kotlin
package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.html.appShell
import com.calypsan.listenup.web.session.SESSION_COOKIE
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.h1
import kotlinx.html.p

/**
 * `GET /` — the gate. Reads `ServerInfo` over loopback: first-run → `/setup`; otherwise a
 * valid session lands on the (placeholder) home, and everyone else is sent to `/login`.
 */
internal fun Route.entryRoutes(deps: WebDependencies) {
    get("/") {
        when (val info = deps.loopback.serverInfo()) {
            is AppResult.Success -> {
                if (info.data.setupRequired) {
                    call.respondRedirect("/setup")
                    return@get
                }
                val cookieId = call.request.cookies[SESSION_COOKIE]
                val session = cookieId?.let { deps.store.get(it) }
                if (session == null) {
                    call.respondRedirect("/login")
                    return@get
                }
                call.respondHtml {
                    appShell(pageTitle = "ListenUp") {
                        h1 { +"ListenUp" }
                        p { +"Your library, in the browser." }
                    }
                }
            }
            is AppResult.Failure -> call.respondRedirect("/login")
        }
    }
}
```

- [ ] **Step 5: Create `LoginRoutes.kt`** (the login half; setup added in Task 5)

```kotlin
package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.html.loginForm
import com.calypsan.listenup.web.html.loginFormFragment
import com.calypsan.listenup.web.html.respondPage
import com.calypsan.listenup.web.security.newCsrfToken
import com.calypsan.listenup.web.security.webCsrfConfig
import com.calypsan.listenup.web.session.WebSession
import com.calypsan.listenup.web.session.setCsrfCookie
import com.calypsan.listenup.web.session.setSessionCookie
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.loginRoutes(deps: WebDependencies) {
    route("/login") {
        install(CSRF, webCsrfConfig)
        get {
            val token = newCsrfToken()
            call.setCsrfCookie(token)
            call.respondPage(title = "Sign in", csrfToken = token) { loginForm() }
        }
        post {
            val params = call.receiveParameters()
            val email = params["email"].orEmpty()
            val password = params["password"].orEmpty()
            when (val result = deps.loopback.login(LoginRequest(email, password))) {
                is AppResult.Success -> {
                    val authSession = result.data
                    val cookieId = deps.store.newCookieId()
                    deps.store.put(
                        cookieId,
                        WebSession(
                            sessionId = authSession.sessionId,
                            userId = authSession.user.id,
                            role = authSession.user.role,
                            accessToken = authSession.accessToken,
                            refreshToken = authSession.refreshToken,
                            accessExpiresAt = authSession.accessTokenExpiresAt,
                        ),
                    )
                    call.setSessionCookie(cookieId)
                    call.response.header("HX-Redirect", "/")
                    call.respondText("", ContentType.Text.Html)
                }
                is AppResult.Failure ->
                    call.respondText(
                        loginFormFragment(email = email, error = result.error.message),
                        ContentType.Text.Html,
                    )
            }
        }
    }
}
```

- [ ] **Step 6: Remove the `loginRoutes` placeholder** from `EntryRoutes.kt` (it now lives in `LoginRoutes.kt`). Leave `registerRoutes`/`accountRoutes` placeholders wherever they currently are until their tasks. Ensure exactly one definition of each function exists (compiler will catch duplicates).

- [ ] **Step 7: Update `WebShellRoutesTest.kt`** — `GET /` now requires loopback, so it can't pass in the in-memory full-module test. Keep only the asset assertions.

Replace the body of `server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt` with:

```kotlin
package com.calypsan.listenup.server

import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

/**
 * Phase 1A served `GET /` here. From Phase 1B `/` requires a loopback REST round-trip
 * (ServerInfo), which the in-memory `testApplication` transport can't satisfy — that flow
 * is covered by the real-port `WebAuthEndToEndTest`. These assertions keep the static-asset
 * surface honest (no loopback needed).
 */
class WebShellRoutesTest :
    FunSpec({
        test("GET /assets/htmx.min.js serves the vendored htmx runtime") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                client.get("/assets/htmx.min.js").status shouldBe HttpStatusCode.OK
            }
        }

        test("GET /assets/app.css serves generated Tailwind utilities") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                client.get("/assets/app.css").status shouldBe HttpStatusCode.OK
            }
        }
    })
```

- [ ] **Step 8: Run the tests**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.routes.LoginRoutesTest"` then `gradle :server:test --tests "com.calypsan.listenup.server.WebShellRoutesTest"`
Expected: PASS (login: 3; shell: 2).

- [ ] **Step 9: Commit**

```bash
git add web/src/main/kotlin/com/calypsan/listenup/web web/src/test/kotlin/com/calypsan/listenup/web/routes/LoginRoutesTest.kt server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt
git commit -m "✨ feat(web): entry routing + login screen over loopback auth

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: First-run setup

`/setup` creates the root user when `setupRequired`; otherwise it bounces to `/login`.

**Files:**
- Modify: `web/src/main/kotlin/com/calypsan/listenup/web/html/AuthPages.kt` (add the setup form)
- Modify: `web/src/main/kotlin/com/calypsan/listenup/web/routes/LoginRoutes.kt` (add the `/setup` route)
- Test: `web/src/test/kotlin/com/calypsan/listenup/web/routes/SetupRoutesTest.kt`

- [ ] **Step 1: Write the failing setup test** `web/src/test/kotlin/com/calypsan/listenup/web/routes/SetupRoutesTest.kt`

```kotlin
package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.testing.installTestWebUi
import com.calypsan.listenup.web.testing.webClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication

private fun serverInfo(setupRequired: Boolean) =
    ServerInfo(
        name = "ListenUp",
        version = "0.0.1",
        apiVersion = "v1",
        setupRequired = setupRequired,
        registrationPolicy = RegistrationPolicy.OPEN,
        instanceId = "i1",
    )

class SetupRoutesTest :
    FunSpec({
        test("GET /setup renders the root-setup form when setup is required") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.serverInfoResult = AppResult.Success(serverInfo(setupRequired = true))
                val client = webClient()

                val response = client.get("/setup")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "name=\"displayName\""
            }
        }

        test("GET /setup redirects to /login when setup is already complete") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.serverInfoResult = AppResult.Success(serverInfo(setupRequired = false))
                val client = webClient()

                val response = client.get("/setup")

                response.status shouldBe HttpStatusCode.Found
                response.headers["Location"] shouldBe "/login"
            }
        }

        test("POST /setup success creates the root session and HX-Redirects home") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.serverInfoResult = AppResult.Success(serverInfo(setupRequired = true))
                ctx.fake.setupResult = AppResult.Success(authSession())
                val client = webClient()
                client.get("/setup")
                val token = client.cookies("http://localhost/").first { it.name == "lu_csrf" }.value

                val response =
                    client.submitForm(
                        url = "/setup",
                        formParameters = parameters {
                            append("email", "root@x")
                            append("displayName", "Root")
                            append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", token) }

                response.headers["HX-Redirect"] shouldBe "/"
            }
        }
    })
```

> NOTE: `authSession()` is the same helper as in `LoginRoutesTest`. To avoid duplication, lift it into `web/src/test/kotlin/com/calypsan/listenup/web/testing/Fixtures.kt` as `internal fun sampleAuthSession()` and call it from both tests (do this lift in this step; update `LoginRoutesTest` to use it).

- [ ] **Step 2: Run to verify it fails**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.routes.SetupRoutesTest"`
Expected: FAIL — `/setup` 404.

- [ ] **Step 3: Add the setup form** to `AuthPages.kt`

```kotlin
/** First-run root-setup form fragment (id `auth-form`). */
fun FlowContent.setupForm(error: String? = null) {
    div {
        id = "auth-form"
        h1 { +"Welcome — create the owner account" }
        if (error != null) p(classes = "text-red-600") { +error }
        form {
            attributes["hx-post"] = "/setup"
            attributes["hx-target"] = "#auth-form"
            attributes["hx-swap"] = "outerHTML"
            label { +"Display name" }
            input(type = InputType.text, name = "displayName")
            label { +"Email" }
            input(type = InputType.email, name = "email")
            label { +"Password" }
            input(type = InputType.password, name = "password")
            button(type = ButtonType.submit) { +"Create account" }
        }
    }
}

/** Render the setup fragment for an htmx error swap. */
fun setupFormFragment(error: String?): String = createHTML().div { setupForm(error) }
```

- [ ] **Step 4: Add the `/setup` route** to `LoginRoutes.kt`

Inside `loginRoutes(deps)`, after the `route("/login") { … }` block, add:

```kotlin
    route("/setup") {
        install(CSRF, webCsrfConfig)
        get {
            when (val info = deps.loopback.serverInfo()) {
                is AppResult.Success ->
                    if (!info.data.setupRequired) {
                        call.respondRedirect("/login")
                    } else {
                        val token = newCsrfToken()
                        call.setCsrfCookie(token)
                        call.respondPage(title = "Set up ListenUp", csrfToken = token) { setupForm() }
                    }
                is AppResult.Failure -> call.respondRedirect("/login")
            }
        }
        post {
            val params = call.receiveParameters()
            val request =
                com.calypsan.listenup.api.dto.auth.RegisterRequest(
                    email = params["email"].orEmpty(),
                    password = params["password"].orEmpty(),
                    displayName = params["displayName"].orEmpty(),
                )
            when (val result = deps.loopback.setup(request)) {
                is AppResult.Success -> {
                    startWebSession(deps, call, result.data)
                    call.response.header("HX-Redirect", "/")
                    call.respondText("", ContentType.Text.Html)
                }
                is AppResult.Failure ->
                    call.respondText(setupFormFragment(result.error.message), ContentType.Text.Html)
            }
        }
    }
```

Add the imports `setupForm`, `setupFormFragment`, `io.ktor.server.response.respondRedirect`, and extract the session-creation block from Task 4 into a shared private helper in `LoginRoutes.kt` (used by login + setup):

```kotlin
import com.calypsan.listenup.api.dto.auth.AuthSession
import io.ktor.server.application.ApplicationCall

private fun startWebSession(deps: WebDependencies, call: ApplicationCall, authSession: AuthSession) {
    val cookieId = deps.store.newCookieId()
    deps.store.put(
        cookieId,
        WebSession(
            sessionId = authSession.sessionId,
            userId = authSession.user.id,
            role = authSession.user.role,
            accessToken = authSession.accessToken,
            refreshToken = authSession.refreshToken,
            accessExpiresAt = authSession.accessTokenExpiresAt,
        ),
    )
    call.setSessionCookie(cookieId)
}
```

Refactor the Task 4 login `Success` branch to call `startWebSession(deps, call, result.data)` instead of inlining the store write (DRY).

- [ ] **Step 5: Run the setup test (and re-run login)**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.routes.SetupRoutesTest" --tests "com.calypsan.listenup.web.routes.LoginRoutesTest"`
Expected: PASS (setup: 3, login: 3).

- [ ] **Step 6: Commit**

```bash
git add web/src/main/kotlin/com/calypsan/listenup/web web/src/test/kotlin/com/calypsan/listenup/web
git commit -m "✨ feat(web): first-run owner setup screen

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Register + Pending approval

Register honors the registration policy; `PendingApproval` routes to `/pending`, which polls the loopback status endpoint (Never-Stranded) **and** subscribes the htmx SSE extension directly to the public, same-origin REST SSE stream (the first SSE consumer).

**Boundary note:** The registration-status SSE endpoint is public and same-origin, so the browser consumes it directly via `htmx-ext-sse` (no BFF proxy, no `ktor-client-sse` dependency). The poll endpoint **is** proxied through the BFF (`GET /pending/status`) and is the testable, always-works baseline. Routing the stream through a BFF proxy is a later option if we decide to fully hide `/api` from the browser.

**Files:**
- Create: `web/src/main/resources/web/htmx-ext-sse.js`
- Modify: `web/src/main/kotlin/com/calypsan/listenup/web/html/AuthPages.kt` (register + pending markup)
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/routes/RegisterRoutes.kt`
- Remove the `registerRoutes` placeholder from `EntryRoutes.kt`.
- Test: `web/src/test/kotlin/com/calypsan/listenup/web/routes/RegisterRoutesTest.kt`

- [ ] **Step 1: Write the failing register/pending test** `web/src/test/kotlin/com/calypsan/listenup/web/routes/RegisterRoutesTest.kt`

```kotlin
package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.testing.installTestWebUi
import com.calypsan.listenup.web.testing.sampleAuthSession
import com.calypsan.listenup.web.testing.webClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication

class RegisterRoutesTest :
    FunSpec({
        suspend fun csrf(client: io.ktor.client.HttpClient) =
            client.cookies("http://localhost/").first { it.name == "lu_csrf" }.value

        test("POST /register Authenticated HX-Redirects home") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.registerResult = AppResult.Success(RegisterResult.Authenticated(sampleAuthSession()))
                val client = webClient()
                client.get("/register")
                val response =
                    client.submitForm(
                        url = "/register",
                        formParameters = parameters {
                            append("email", "a@x")
                            append("displayName", "A")
                            append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", csrf(client)) }
                response.headers["HX-Redirect"] shouldBe "/"
            }
        }

        test("POST /register PendingApproval HX-Redirects to /pending with the user id") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.registerResult = AppResult.Success(RegisterResult.PendingApproval(UserId("u9")))
                val client = webClient()
                client.get("/register")
                val response =
                    client.submitForm(
                        url = "/register",
                        formParameters = parameters {
                            append("email", "a@x")
                            append("displayName", "A")
                            append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", csrf(client)) }
                response.headers["HX-Redirect"] shouldBe "/pending?userId=u9"
            }
        }

        test("GET /pending renders SSE + poll wiring for the user id") {
            testApplication {
                installTestWebUi()
                val client = webClient()
                val response = client.get("/pending?userId=u9")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "/api/v1/auth/registration-status/u9/stream"
                html shouldContain "/pending/status?userId=u9"
            }
        }

        test("GET /pending/status approved HX-Redirects to /login") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.registrationStatusResult = AppResult.Success(RegistrationStatusEvent(status = "approved"))
                val client = webClient()
                val response = client.get("/pending/status?userId=u9")
                response.headers["HX-Redirect"] shouldBe "/login"
            }
        }

        test("GET /pending/status denied shows the reason") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.registrationStatusResult =
                    AppResult.Success(RegistrationStatusEvent(status = "denied", message = "No room at the inn."))
                val client = webClient()
                val response = client.get("/pending/status?userId=u9")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "No room at the inn."
            }
        }
    })
```

> NOTE: Add `internal fun sampleAuthSession()` to `testing/Fixtures.kt` (the lift from Task 5).

- [ ] **Step 2: Run to verify it fails**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.routes.RegisterRoutesTest"`
Expected: FAIL — routes 404.

- [ ] **Step 3: Vendor the htmx SSE extension** `web/src/main/resources/web/htmx-ext-sse.js`

```bash
mkdir -p web/src/main/resources/web
curl -fsSL https://unpkg.com/htmx-ext-sse/dist/sse.js -o web/src/main/resources/web/htmx-ext-sse.js
test -s web/src/main/resources/web/htmx-ext-sse.js && echo "sse ext vendored"
```

Then append its version + SHA-256 to `web/VENDORED.md` (mirror the htmx entry created in Phase 1A):

```bash
{
  echo ""
  echo "## htmx-ext-sse"
  echo "- source: https://unpkg.com/htmx-ext-sse/dist/sse.js"
  echo "- sha256: $(sha256sum web/src/main/resources/web/htmx-ext-sse.js | cut -d' ' -f1)"
} >> web/VENDORED.md
```

- [ ] **Step 4: Add register + pending markup** to `AuthPages.kt`

```kotlin
/** Self-registration form fragment (id `auth-form`). */
fun FlowContent.registerForm(error: String? = null) {
    div {
        id = "auth-form"
        h1 { +"Create your account" }
        if (error != null) p(classes = "text-red-600") { +error }
        form {
            attributes["hx-post"] = "/register"
            attributes["hx-target"] = "#auth-form"
            attributes["hx-swap"] = "outerHTML"
            label { +"Display name" }
            input(type = InputType.text, name = "displayName")
            label { +"Email" }
            input(type = InputType.email, name = "email")
            label { +"Password" }
            input(type = InputType.password, name = "password")
            button(type = ButtonType.submit) { +"Register" }
        }
    }
}

fun registerFormFragment(error: String?): String = createHTML().div { registerForm(error) }

/**
 * The pending-approval page body. Real-time path: htmx SSE extension subscribes directly to
 * the public, same-origin registration-status stream. Never-Stranded fallback: poll the BFF
 * status endpoint every few seconds. Both target `#pending-status`, which swaps in the
 * approved/denied fragment (an `HX-Redirect` on approval navigates away).
 */
fun FlowContent.pendingBody(userId: String) {
    div {
        attributes["hx-ext"] = "sse"
        attributes["sse-connect"] = "/api/v1/auth/registration-status/$userId/stream"
        div {
            id = "pending-status"
            attributes["hx-get"] = "/pending/status?userId=$userId"
            attributes["hx-trigger"] = "sse:message, every 5s"
            attributes["hx-swap"] = "innerHTML"
            p { +"Your account is awaiting administrator approval…" }
        }
    }
}
```

> NOTE: Add `script(src = "/assets/htmx-ext-sse.js")` to the app-shell head (after the htmx runtime `script`), so the `hx-ext="sse"` attribute resolves. Update `AppShellTest` is not required (the extension tag isn't asserted), but add the script line to `AppShell.kt`.

- [ ] **Step 5: Create `RegisterRoutes.kt`**

```kotlin
package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.html.pendingBody
import com.calypsan.listenup.web.html.registerForm
import com.calypsan.listenup.web.html.registerFormFragment
import com.calypsan.listenup.web.html.respondPage
import com.calypsan.listenup.web.security.newCsrfToken
import com.calypsan.listenup.web.security.webCsrfConfig
import com.calypsan.listenup.web.session.setCsrfCookie
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.html.p

internal fun Route.registerRoutes(deps: WebDependencies) {
    route("/register") {
        install(CSRF, webCsrfConfig)
        get {
            val token = newCsrfToken()
            call.setCsrfCookie(token)
            call.respondPage(title = "Register", csrfToken = token) { registerForm() }
        }
        post {
            val params = call.receiveParameters()
            val request =
                RegisterRequest(
                    email = params["email"].orEmpty(),
                    password = params["password"].orEmpty(),
                    displayName = params["displayName"].orEmpty(),
                )
            when (val result = deps.loopback.register(request)) {
                is AppResult.Success ->
                    when (val outcome = result.data) {
                        is RegisterResult.Authenticated -> {
                            startWebSession(deps, call, outcome.session)
                            call.response.header("HX-Redirect", "/")
                            call.respondText("", ContentType.Text.Html)
                        }
                        is RegisterResult.PendingApproval -> {
                            call.response.header("HX-Redirect", "/pending?userId=${outcome.userId.value}")
                            call.respondText("", ContentType.Text.Html)
                        }
                    }
                is AppResult.Failure ->
                    call.respondText(registerFormFragment(result.error.message), ContentType.Text.Html)
            }
        }
    }

    // Pending screen + poll fallback. GET-only (no CSRF needed).
    get("/pending") {
        val userId = call.request.queryParameters["userId"].orEmpty()
        call.respondPage(title = "Awaiting approval", csrfToken = null) { pendingBody(userId) }
    }
    get("/pending/status") {
        val userId = call.request.queryParameters["userId"].orEmpty()
        when (val status = deps.loopback.registrationStatus(UserId(userId))) {
            is AppResult.Success ->
                when (status.data.status) {
                    "approved" -> {
                        call.response.header("HX-Redirect", "/login")
                        call.respondText("", ContentType.Text.Html)
                    }
                    "denied" ->
                        call.respondText(
                            "<p class=\"text-red-600\">${status.data.message ?: "Your registration was denied."}</p>",
                            ContentType.Text.Html,
                        )
                    else ->
                        call.respondText(
                            "<p>Your account is awaiting administrator approval…</p>",
                            ContentType.Text.Html,
                        )
                }
            is AppResult.Failure ->
                call.respondText("<p>Checking status…</p>", ContentType.Text.Html)
        }
    }
}
```

> NOTE: `startWebSession` is the private helper added in Task 5 (`LoginRoutes.kt`). Either make it `internal` in a shared routes file (e.g. `web/.../routes/SessionStart.kt`) so `RegisterRoutes.kt` can call it, or duplicate the tiny body. Prefer extracting it to `internal fun startWebSession(...)` in its own small file and importing it in both `LoginRoutes.kt` and `RegisterRoutes.kt`.

- [ ] **Step 6: Run the test**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.routes.RegisterRoutesTest"`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add web/src/main/kotlin/com/calypsan/listenup/web web/src/main/resources/web/htmx-ext-sse.js web/VENDORED.md web/src/test/kotlin/com/calypsan/listenup/web/routes/RegisterRoutesTest.kt
git commit -m "✨ feat(web): registration + pending-approval (SSE + poll)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Logout + Active sessions

`requireWebSession`-guarded routes: logout clears the store + cookie; the sessions screen lists devices and revokes them.

**Files:**
- Modify: `web/src/main/kotlin/com/calypsan/listenup/web/html/AuthPages.kt` (sessions list markup)
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/routes/AccountRoutes.kt`
- Remove the `accountRoutes` placeholder from `EntryRoutes.kt`.
- Test: `web/src/test/kotlin/com/calypsan/listenup/web/routes/AccountRoutesTest.kt`

- [ ] **Step 1: Write the failing test** `web/src/test/kotlin/com/calypsan/listenup/web/routes/AccountRoutesTest.kt`

```kotlin
package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.session.WebSession
import com.calypsan.listenup.web.session.WebSessionStore
import com.calypsan.listenup.web.testing.installTestWebUi
import com.calypsan.listenup.web.testing.webClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

private fun seededSession(store: WebSessionStore): String {
    val cookieId = store.newCookieId()
    store.put(
        cookieId,
        WebSession(
            sessionId = SessionId("s1"),
            userId = UserId("u1"),
            role = UserRole.MEMBER,
            accessToken = AccessToken("at"),
            refreshToken = RefreshToken("rt"),
            accessExpiresAt = 9_999_999_999_999L,
        ),
    )
    return cookieId
}

class AccountRoutesTest :
    FunSpec({
        test("POST /logout without a session cookie redirects to /login") {
            testApplication {
                installTestWebUi()
                val client = webClient()
                val response = client.post("/logout")
                response.status shouldBe HttpStatusCode.Found
                response.headers["Location"] shouldBe "/login"
            }
        }

        test("POST /logout clears the store entry and expires the cookie") {
            testApplication {
                val store = WebSessionStore()
                installTestWebUi(store = store)
                val cookieId = seededSession(store)
                val client = webClient()

                val response =
                    client.post("/logout") {
                        cookie("lu_session", cookieId)
                        cookie("lu_csrf", "t1")
                        header("X-CSRF-Token", "t1")
                    }

                response.headers["HX-Redirect"] shouldBe "/login"
                store.get(cookieId).shouldBeNull()
            }
        }

        test("GET /account/sessions lists the device sessions") {
            testApplication {
                val store = WebSessionStore()
                val ctx = installTestWebUi(store = store)
                ctx.fake.listSessionsResult =
                    AppResult.Success(
                        listOf(
                            SessionSummary(
                                id = SessionId("s1"),
                                label = "Firefox on Linux",
                                createdAt = 0L,
                                lastUsedAt = 0L,
                                current = true,
                            ),
                        ),
                    )
                val cookieId = seededSession(store)
                val client = webClient()

                val response = client.get("/account/sessions") { cookie("lu_session", cookieId) }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Firefox on Linux"
            }
        }

        test("DELETE /account/sessions/{id} revokes via the loopback client") {
            testApplication {
                val store = WebSessionStore()
                val ctx = installTestWebUi(store = store)
                ctx.fake.revokeResult = AppResult.Success(Unit)
                val cookieId = seededSession(store)
                val client = webClient()

                val response =
                    client.delete("/account/sessions/s1") {
                        cookie("lu_session", cookieId)
                        cookie("lu_csrf", "t1")
                        header("X-CSRF-Token", "t1")
                    }

                response.status shouldBe HttpStatusCode.OK
            }
        }
    })
```

- [ ] **Step 2: Run to verify it fails**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.routes.AccountRoutesTest"`
Expected: FAIL — routes 404.

- [ ] **Step 3: Add the sessions list markup** to `AuthPages.kt`

```kotlin
import com.calypsan.listenup.api.dto.auth.SessionSummary
import kotlinx.html.li
import kotlinx.html.span
import kotlinx.html.ul

/** Active-sessions list. Each row can revoke itself via `hx-delete` (except the current one). */
fun FlowContent.sessionsList(sessions: List<SessionSummary>) {
    div {
        id = "sessions"
        h1 { +"Active sessions" }
        ul {
            sessions.forEach { summary ->
                li {
                    span { +(summary.label ?: "Unknown device") }
                    if (summary.current) {
                        span { +" (this device)" }
                    } else {
                        button(type = ButtonType.button) {
                            attributes["hx-delete"] = "/account/sessions/${summary.id.value}"
                            attributes["hx-target"] = "#sessions"
                            attributes["hx-swap"] = "outerHTML"
                            +"Revoke"
                        }
                    }
                }
            }
        }
    }
}

fun sessionsListFragment(sessions: List<SessionSummary>): String = createHTML().div { sessionsList(sessions) }
```

- [ ] **Step 4: Create `AccountRoutes.kt`**

```kotlin
package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.html.respondPage
import com.calypsan.listenup.web.html.sessionsList
import com.calypsan.listenup.web.html.sessionsListFragment
import com.calypsan.listenup.web.security.newCsrfToken
import com.calypsan.listenup.web.security.webCsrfConfig
import com.calypsan.listenup.web.session.SESSION_COOKIE
import com.calypsan.listenup.web.session.expireSessionCookie
import com.calypsan.listenup.web.session.requireWebSession
import com.calypsan.listenup.web.session.setCsrfCookie
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.accountRoutes(deps: WebDependencies) {
    route("/logout") {
        install(CSRF, webCsrfConfig)
        post {
            val ctx = call.requireWebSession(deps.store, deps.authenticator) ?: return@post
            deps.loopback.logout(ctx.accessToken)
            val cookieId = call.request.cookies[SESSION_COOKIE]
            if (cookieId != null) deps.store.remove(cookieId)
            call.expireSessionCookie()
            call.response.header("HX-Redirect", "/login")
            call.respondText("", ContentType.Text.Html)
        }
    }

    route("/account/sessions") {
        install(CSRF, webCsrfConfig)
        get {
            val ctx = call.requireWebSession(deps.store, deps.authenticator) ?: return@get
            when (val result = deps.loopback.listSessions(ctx.accessToken)) {
                is AppResult.Success -> {
                    val token = newCsrfToken()
                    call.setCsrfCookie(token)
                    call.respondPage(title = "Sessions", csrfToken = token) { sessionsList(result.data) }
                }
                is AppResult.Failure ->
                    call.respondText("<p>${result.error.message}</p>", ContentType.Text.Html)
            }
        }
        delete("/{id}") {
            val ctx = call.requireWebSession(deps.store, deps.authenticator) ?: return@delete
            val id = SessionId(call.parameters["id"].orEmpty())
            deps.loopback.revokeSession(ctx.accessToken, id)
            when (val refreshed = deps.loopback.listSessions(ctx.accessToken)) {
                is AppResult.Success ->
                    call.respondText(sessionsListFragment(refreshed.data), ContentType.Text.Html)
                is AppResult.Failure ->
                    call.respondText("", ContentType.Text.Html, HttpStatusCode.OK)
            }
        }
    }
}
```

> NOTE: logout's CSRF lambda reads the `lu_csrf` cookie. Because logout is reached after login (which set `lu_csrf` during the form GET), the cookie is present; tests set it explicitly. The `delete("/{id}")` route under `route("/account/sessions")` resolves to `/account/sessions/{id}`.

- [ ] **Step 5: Run the test**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.routes.AccountRoutesTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Run the entire `:web` suite**

Run: `gradle :web:test`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add web/src/main/kotlin/com/calypsan/listenup/web web/src/test/kotlin/com/calypsan/listenup/web/routes/AccountRoutesTest.kt
git commit -m "✨ feat(web): logout + active-sessions management

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Real-port end-to-end + boundary hardening + verification

Prove the loopback dogfooding actually round-trips to the real REST + SQLite, harden the 1A backlog items, and run the full gate.

**Files:**
- Create: `server/src/test/kotlin/com/calypsan/listenup/server/e2e/WebAuthEndToEndTest.kt`
- Modify: `web/src/test/kotlin/com/calypsan/listenup/web/konsist/WebBoundaryKonsistTest.kt`

- [ ] **Step 1: Write the real-port e2e test** `server/src/test/kotlin/com/calypsan/listenup/server/e2e/WebAuthEndToEndTest.kt`

This boots the **full** `module()` on a real CIO connector at a fixed free port (so the BFF's `loopbackBaseUrl` resolves to the same port via `ktor.deployment.port`), then drives the web routes with a real cookie-aware HTTP client — exercising web route → real auth REST → real SQLite over real loopback HTTP.

```kotlin
package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.server.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import java.net.ServerSocket
import java.nio.file.Files

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

class WebAuthEndToEndTest :
    FunSpec({
        test("web setup → home, then login round-trips through the real loopback REST") {
            val port = freePort()
            val tmpDb = Files.createTempFile("listenup-web-e2e-", ".db").toFile().apply { deleteOnExit() }
            val env =
                applicationEnvironment {
                    config =
                        MapApplicationConfig(
                            "database.jdbcUrl" to "jdbc:sqlite:${tmpDb.absolutePath}",
                            "auth.refreshPepper" to "x".repeat(32),
                            "jwt.secret" to "x".repeat(32),
                            "jwt.issuer" to "listenup",
                            "jwt.audience" to "listenup-client",
                            "registration.policy" to "OPEN",
                            "mdns.enabled" to "false",
                            "ktor.deployment.port" to port.toString(),
                        )
                }
            val server =
                embeddedServer(
                    factory = ServerCIO,
                    environment = env,
                    configure = {
                        connectors.add(
                            EngineConnectorBuilder().apply {
                                host = "127.0.0.1"
                                this.port = port
                            },
                        )
                    },
                ) {
                    module()
                }
            server.start(wait = false)
            val baseUrl = "http://127.0.0.1:$port"
            val client =
                HttpClient(CIO) {
                    install(HttpCookies)
                    followRedirects = false
                    defaultRequest { url(baseUrl) }
                }
            try {
                // Fresh server → GET / redirects to /setup.
                client.get("/").headers["Location"] shouldBe "/setup"

                // Setup the root account through the BFF (real REST + SQLite).
                client.get("/setup")
                val token = client.cookies(baseUrl).first { it.name == "lu_csrf" }.value
                val setup =
                    client.submitForm(
                        url = "/setup",
                        formParameters = parameters {
                            append("email", "root@x")
                            append("displayName", "Root")
                            append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", token) }
                setup.headers["HX-Redirect"] shouldBe "/"

                // The session cookie now authenticates the home page.
                val home = client.get("/")
                home.bodyAsText() shouldContain "Your library, in the browser."
            } finally {
                client.close()
                @Suppress("MagicNumber")
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }
    })
```

> NOTE: import the server engine as `io.ktor.server.cio.CIO as ServerCIO` to avoid clashing with the client `io.ktor.client.engine.cio.CIO`. `freePort()` has a tiny TOCTOU window; acceptable for a test. This mirrors `AuthEndToEndFixture` but uses a *fixed* port so the in-process BFF's `loopbackBaseUrl` (derived from `ktor.deployment.port`) matches the bound connector.

- [ ] **Step 2: Run the e2e test**

Run: `gradle :server:test --tests "com.calypsan.listenup.server.e2e.WebAuthEndToEndTest"`
Expected: PASS. (If `module()` startup needs more config keys than listed, copy the missing keys from `AuthEndToEndFixture` / `useIsolatedTestConfig` — both are the source of truth for the minimal config.)

- [ ] **Step 3: Harden the Konsist boundary guard (1A backlog: non-vacuity)**

Replace `web/src/test/kotlin/com/calypsan/listenup/web/konsist/WebBoundaryKonsistTest.kt` with:

```kotlin
package com.calypsan.listenup.web.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan

/**
 * Structural BFF boundary: `:web` must reach the domain only through the REST API, never by
 * importing `:server`. The non-vacuity assertion stops this guard from passing trivially if
 * the scope ever resolves to zero files.
 */
class WebBoundaryKonsistTest :
    FunSpec({
        val webFiles =
            Konsist
                .scopeFromProduction()
                .files
                .filter { it.path.contains("/web/src/main/") }

        test("the boundary scope actually finds :web production files") {
            webFiles.size shouldBeGreaterThan 0
        }

        test(":web has no import from the :server module") {
            val offenders =
                webFiles.flatMap { file ->
                    file.imports
                        .filter { it.name.startsWith("com.calypsan.listenup.server.") }
                        .map { "${file.path} -> ${it.name}" }
                }
            offenders.shouldBeEmpty()
        }
    })
```

- [ ] **Step 4: Run the Konsist guard**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.konsist.WebBoundaryKonsistTest"`
Expected: PASS (2 tests; non-vacuity finds the new `:web` files).

- [ ] **Step 5: Run the full gate**

Run: `gradle :web:test :server:test detekt`
Expected: all PASS. Confirm no new detekt findings were baselined: `git diff --stat -- detekt-baseline.xml` shows no change (the file must remain byte-identical to `main`, per the 1A standard — resolve findings, don't baseline them).

- [ ] **Step 6: Boot and eyeball (optional manual check)**

Run: `gradle :server:run`, then visit `http://127.0.0.1:8080/` — a fresh DB lands on `/setup`; create the owner, land on the placeholder home; sign out via `POST /logout`; sign back in at `/login`. Confirm `/api/docs` still lists the auth endpoints (Phase 1A). Stop with Ctrl-C.

- [ ] **Step 7: Commit**

```bash
git add server/src/test/kotlin/com/calypsan/listenup/server/e2e/WebAuthEndToEndTest.kt web/src/test/kotlin/com/calypsan/listenup/web/konsist/WebBoundaryKonsistTest.kt
git commit -m "✅ test(web): real-loopback e2e + non-vacuous BFF boundary guard

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review (completed by plan author)

**Spec coverage (Phase 1 §2.3, §3, §3.2, §3.3, §4, §6, §7):**
- §2.3 Loopback REST client (typed over `:contract`, bearer, AppResult/AppError decoding, bare `ServerInfo`) → Task 1. ✓
- §3.1 Server-side in-memory web-session store → Task 2 (`WebSessionStore`). ✓
- §3.1 Single-flight refresh (rotation-safe) → Task 2 (`WebSessionAuthenticator`, invariant-layer test). ✓
- §3.2 `requireWebSession` (cookie → load → refresh → redirect) → Task 3 (`WebSessions.kt`), used in Task 7. ✓
- §3.3 CSRF (double-submit token via `ktor-server-csrf`, per-route) → Task 3; `originMatchesHost` deliberately omitted with rationale. ✓
- §4 Entry/Login → Task 4; Setup → Task 5; Register/Pending (first SSE consumer + poll) → Task 6; Logout/Active-sessions → Task 7. ✓
- §2.4 App-shell CSRF head slot + static SSE-ext asset → Task 3 + Task 6. ✓
- §6 Testing: login good/bad, setup, register→pending, logout, CSRF reject, `requireWebSession` redirect, active-sessions list/revoke, single-flight, real round-trip → Tasks 1–8. ✓
- §7 Boundary: active-sessions **in** (Task 7); invite-code claiming + post-login content **deferred** (not in this plan). ✓
- 1A backlog folded in: Konsist non-vacuity (Task 8). htmx provenance strengthening + OpenAPI single-source-of-truth remain open (out of Phase 1B scope; tracked in the effort memory).

**Deferred deliberately (documented):** `originMatchesHost()` CSRF hardening; BFF SSE proxy (browser consumes the public same-origin REST SSE directly); fully-vendored Swagger UI; the access-token-expiry *transparent-refresh through real REST* (covered at the invariant layer in Task 2, since a real 15-min JWT can't be expired deterministically in a test).

**Placeholder scan:** The empty route bodies in Task 3 Step 10 are compilable scaffolding, each replaced by a specific later task (4/5/6/7) — not omitted content. Inline `NOTE`s flag Ktor-3.5 signature checks (CSRF lambda receivers, cookie `SameSite` via `extensions`, `origin.scheme` import) — these are real execution notes with a fixed intended behavior, matching the 1A plan's style.

**Type consistency:** `installWebUi(WebUiConfig)` / `installWebUi(WebDependencies)` overloads (Task 3) ↔ call site in `:server`'s `Application.kt` (unchanged from 1A — still `installWebUi(WebUiConfig(loopbackBaseUrl = …))`). `LoopbackAuthClient` interface (Task 1) ↔ `FakeLoopbackAuthClient` (Task 2) ↔ `KtorLoopbackAuthClient` (Task 1) — same nine methods. `WebSession(sessionId, userId, role, accessToken, refreshToken, accessExpiresAt)` constructor identical across Tasks 2/4/5/7. `startWebSession(deps, call, authSession)` defined Task 5, reused Task 6. `webCsrfConfig` / `CSRF_COOKIE` / `CSRF_HEADER` consistent across Tasks 3/4/5/6/7. `respondPage` / `appShell(pageTitle, csrfToken, content)` consistent. `sampleAuthSession()` fixture lifted in Task 5, used in Tasks 4/5/6. ✓

**Litmus test (the architecture's own):** every screen render is a live loopback REST call; renaming a `:contract` DTO field breaks `:web` and `:server` together at compile time; the BFF can't bypass REST (Konsist + module graph). Holds.

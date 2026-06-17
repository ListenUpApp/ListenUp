# Web Client — Phase 1A: Foundation & OpenAPI Generation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up an embedded `:web` Gradle module that serves a Tailwind-styled placeholder page from the Kotlin server, plus code-first OpenAPI/Swagger documentation for the existing auth REST endpoints — establishing the per-route documentation pattern and the structural BFF boundary for the rest of the web client.

**Architecture:** A new `:web` JVM module depends on `:contract` only (never `:server`), so it cannot bypass REST — the BFF boundary is a compile-time guarantee enforced by Konsist. `:server` depends on `:web` and mounts its routes via `installWebUi(...)`. OpenAPI is generated from code (not hand-written) and served as Swagger UI; a drift-guard test keeps the spec honest.

**Tech Stack:** Kotlin (JVM 21), Ktor 3.5 (CIO server), `kotlinx.html` + `ktor-server-html-builder`, TailwindCSS standalone CLI, `ktor-server-openapi` + `ktor-server-swagger`, Kotest `FunSpec` + `ktor-server-test-host`, Konsist.

**Worktree:** `/home/simonh/Code/lu-web` (branch `feat/web-client`). All paths below are relative to this worktree root unless absolute. Run all `git`/`gradle` commands from the worktree root.

**Spec:** `docs/superpowers/specs/2026-06-17-web-client-phase-1-auth-design.md` (§2 module, §5 OpenAPI) and `…-web-client-architecture-design.md` (§3 architecture, §4 cross-cutting).

**Conventions to mirror (verified in-repo):**
- Module build: `id("listenup.jvm")`; deps as `implementation(projects.contract)` and `implementation(libs.ktor.server.x)`.
- Tests: Kotest `FunSpec`, `io.ktor.server.testing.testApplication { useIsolatedTestConfig(); application { module() } }`, run with `gradle :<module>:test`.
- The canonical JSON is `com.calypsan.listenup.api.contractJson`.
- REST handlers fold `AppResult<T>` to an HTTP status + an `AppResult<T>` JSON body (see `server/.../routes/AuthRoutes.kt`).

---

## File Structure

**Created:**
- `web/build.gradle.kts` — the `:web` module build (depends on `:contract` + Ktor server html/resources + client).
- `web/src/main/kotlin/com/calypsan/listenup/web/WebUi.kt` — `installWebUi(config)` entry point + `WebUiConfig` data class.
- `web/src/main/kotlin/com/calypsan/listenup/web/routes/ShellRoutes.kt` — placeholder `GET /` route.
- `web/src/main/kotlin/com/calypsan/listenup/web/html/AppShell.kt` — `kotlinx.html` page-shell layout helper.
- `web/src/main/resources/web/htmx.min.js` — vendored htmx runtime (static asset).
- `web/src/main/tailwind/input.css` — Tailwind entry stylesheet.
- `web/tailwind.config.js` — Tailwind content globs (scans `:web` `.kt` files).
- `web/src/test/kotlin/com/calypsan/listenup/web/konsist/WebBoundaryKonsistTest.kt` — boundary rule test.
- `server/src/main/kotlin/com/calypsan/listenup/server/openapi/OpenApiDocument.kt` — code-first OpenAPI document assembly for the auth surface.
- `server/src/main/kotlin/com/calypsan/listenup/server/openapi/OpenApiRoutes.kt` — serves the spec JSON + Swagger UI.
- `server/src/test/kotlin/com/calypsan/listenup/server/openapi/OpenApiAuthDriftTest.kt` — drift-guard test.
- `server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt` — end-to-end shell render test (web mounted in the full app).

**Modified:**
- `settings.gradle.kts` — add `include(":web")`.
- `gradle/libs.versions.toml` — add `kotlinx-html`, `ktor-server-html-builder` (+ version), reuse existing ktor aliases.
- `server/build.gradle.kts` — add `implementation(projects.web)`.
- `server/src/main/kotlin/com/calypsan/listenup/server/Application.kt` — call `installWebUi(...)` and mount OpenAPI/Swagger routes inside `installAppRoutes`.

---

## Task 1: Create the `:web` module and mount it into `:server`

**Files:**
- Modify: `settings.gradle.kts`
- Create: `web/build.gradle.kts`
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/WebUi.kt`
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/routes/ShellRoutes.kt`
- Modify: `server/build.gradle.kts`
- Modify: `server/src/main/kotlin/com/calypsan/listenup/server/Application.kt`
- Test: `server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt`

- [ ] **Step 1: Write the failing end-to-end test** (lives in `:server` because it boots the full app)

Create `server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt`:

```kotlin
package com.calypsan.listenup.server

import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class WebShellRoutesTest :
    FunSpec({
        test("GET / serves the web shell HTML") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val response = client.get("/")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "ListenUp"
            }
        }
    })
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle :server:test --tests "com.calypsan.listenup.server.WebShellRoutesTest"`
Expected: FAIL — compilation error (`installWebUi` unresolved) or 404 on `/`.

- [ ] **Step 3: Register the module in `settings.gradle.kts`**

Add this line alongside the other `include(...)` calls (after `include(":server")`):

```kotlin
include(":web")
```

- [ ] **Step 4: Create `web/build.gradle.kts`**

```kotlin
plugins {
    id("listenup.jvm")
    alias(libs.plugins.kotlinSerialization)
}

group = "com.calypsan.listenup"
version = "0.0.1"

dependencies {
    implementation(projects.contract)

    // Ktor server surface used by the embedded web routes (mounted by :server).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.kotlinx.html)

    // Loopback REST client (used in Phase 1B; declared now so the module is self-contained).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Logging
    implementation(libs.kotlin.logging)

    // Test
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.konsist)
}

kotlin {
    compilerOptions {
        // :contract is compiled by a pre-release Kotlin; allow consuming its classfiles.
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

tasks.test {
    useJUnitPlatform()
}
```

> NOTE: `libs.ktor.server.html.builder` and `libs.kotlinx.html` are added to the catalog in Task 3. If you are executing strictly task-by-task, this build will not resolve until Task 3 — that is expected; the test in this task fails at compile/resolve, which still satisfies "verify it fails". You may instead run Task 3's Step 1 (catalog additions) before Task 1 Step 5. The ordering note is intentional and not a placeholder.

- [ ] **Step 5: Create `web/src/main/kotlin/com/calypsan/listenup/web/WebUi.kt`**

```kotlin
package com.calypsan.listenup.web

import com.calypsan.listenup.web.routes.shellRoutes
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing

/**
 * Configuration handed to the embedded web UI by `:server` at mount time.
 *
 * @property loopbackBaseUrl Base URL the BFF uses to call the server's own REST API
 *   (e.g. "http://127.0.0.1:8080"). Consumed in Phase 1B by the loopback client.
 */
data class WebUiConfig(
    val loopbackBaseUrl: String,
)

/**
 * Mount the embedded HTMX web UI into the host Ktor application.
 *
 * `:web` depends only on `:contract`; it reaches the domain exclusively through the
 * server's REST API over [WebUiConfig.loopbackBaseUrl]. This is the single integration
 * point `:server` calls from its routing setup.
 */
fun Application.installWebUi(config: WebUiConfig) {
    routing {
        staticResources("/assets", "web")
        shellRoutes()
    }
}
```

- [ ] **Step 6: Create `web/src/main/kotlin/com/calypsan/listenup/web/routes/ShellRoutes.kt`** (minimal placeholder; replaced with the real shell in Task 3)

```kotlin
package com.calypsan.listenup.web.routes

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Placeholder landing route. Real app-shell HTML is added in Task 3. */
fun Route.shellRoutes() {
    get("/") {
        call.respondText(
            "<!doctype html><title>ListenUp</title><h1>ListenUp</h1>",
            ContentType.Text.Html,
        )
    }
}
```

- [ ] **Step 7: Add the `:web` dependency to `server/build.gradle.kts`**

In the `dependencies { }` block, immediately after `implementation(projects.contract)`, add:

```kotlin
    implementation(projects.web)
```

- [ ] **Step 8: Mount the web UI in `Application.kt`**

In `server/src/main/kotlin/com/calypsan/listenup/server/Application.kt`, add the import near the other imports:

```kotlin
import com.calypsan.listenup.web.WebUiConfig
import com.calypsan.listenup.web.installWebUi
```

Then, in `Application.module()`, immediately after the existing `installAppRoutes(homeDir)` call (around line 352), add:

```kotlin
    installWebUi(WebUiConfig(loopbackBaseUrl = "http://127.0.0.1:$httpPort"))
```

> `httpPort` is already resolved earlier in `module()` (the `environment.config.propertyOrNull("ktor.deployment.port")...` block, ~line 284). If `httpPort` is not in scope at the call site, hoist the existing port-resolution expression above this call — do not duplicate it.

- [ ] **Step 9: Run the test to verify it passes**

Run: `gradle :server:test --tests "com.calypsan.listenup.server.WebShellRoutesTest"`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts web/build.gradle.kts web/src/main/kotlin server/build.gradle.kts server/src/main/kotlin/com/calypsan/listenup/server/Application.kt server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt
git commit -m "🧱 feat(web): embed :web module and mount placeholder shell in :server

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: OpenAPI code-first generation + Swagger UI for the auth surface (the spike)

> **This is a spike — discovery is the deliverable.** Its acceptance is empirical: Swagger UI at `/api/docs` renders the auth endpoints with request/response schemas derived from the `@Serializable` DTOs, and the drift-guard test passes. Two implementation routes are given; **the fallback is fully specified so the task can never be stranded.** Prefer the library route only if it integrates cleanly against Ktor 3.5 within a short timebox; otherwise implement the fallback. Whatever you choose becomes the per-route documentation pattern for every later phase — record the decision in a comment at the top of `OpenApiDocument.kt`.

**Files:**
- Create: `server/src/main/kotlin/com/calypsan/listenup/server/openapi/OpenApiDocument.kt`
- Create: `server/src/main/kotlin/com/calypsan/listenup/server/openapi/OpenApiRoutes.kt`
- Modify: `server/src/main/kotlin/com/calypsan/listenup/server/Application.kt`
- Test: `server/src/test/kotlin/com/calypsan/listenup/server/openapi/OpenApiAuthDriftTest.kt`

- [ ] **Step 1: Write the failing drift-guard test**

This test pins the *contract* of the spike regardless of implementation: the served OpenAPI JSON must describe every auth path, and the served Swagger UI must load.

Create `server/src/test/kotlin/com/calypsan/listenup/server/openapi/OpenApiAuthDriftTest.kt`:

```kotlin
package com.calypsan.listenup.server.openapi

import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class OpenApiAuthDriftTest :
    FunSpec({
        // Every auth REST path that must appear in the generated spec.
        val authPaths =
            listOf(
                "/api/v1/auth/login",
                "/api/v1/auth/register",
                "/api/v1/auth/setup",
                "/api/v1/auth/refresh",
                "/api/v1/auth/logout",
                "/api/v1/auth/logout/all",
                "/api/v1/auth/current-user",
                "/api/v1/auth/sessions",
                "/api/v1/auth/sessions/{id}",
            )

        test("the generated OpenAPI document describes every auth path") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val body = client.get("/api/openapi.json").bodyAsText()
                val paths = Json.parseToJsonElement(body).jsonObject["paths"]!!.jsonObject

                authPaths.forEach { path ->
                    (path in paths.keys) shouldBe true
                }
            }
        }

        test("Swagger UI is served at /api/docs") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val response = client.get("/api/docs")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "swagger"
            }
        }
    })
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle :server:test --tests "com.calypsan.listenup.server.openapi.OpenApiAuthDriftTest"`
Expected: FAIL — `/api/openapi.json` and `/api/docs` return 404.

- [ ] **Step 3: Investigate the library route (timeboxed)**

Check Maven Central for a code-first OpenAPI library compatible with **Ktor 3.5 + `ktor-server-resources` + kotlinx.serialization**. Candidates to evaluate (verify current artifact + Ktor 3.5 support before adding — do not assume):
- `tegral-openapi` (`guru.zoroark.tegral:tegral-openapi-ktor`, `…-dsl`, `…-scriptdef`).
- `dev.forst:ktor-openapi-generator` / Papsign `ktor-openapi-tools` (historically lagged Ktor majors — verify 3.5).

Acceptance for adopting a library: it produces an OpenAPI document whose `paths` include all nine auth paths with request/response schemas reflecting the auth DTOs, served alongside Swagger UI, with no Ktor-version conflicts in `gradle :server:dependencies`. If it does not integrate cleanly within the timebox, proceed to Step 4 (fallback). Record either way in a top-of-file comment.

- [ ] **Step 4: Implement the fallback — schema-derived, code-first document (guaranteed path)**

Derive JSON schemas from the DTO `SerialDescriptor`s and assemble the OpenAPI document in code. Each route is declared once with its operation metadata, so the document is generated from code (not hand-maintained) and grows by adding entries to one list.

Create `server/src/main/kotlin/com/calypsan/listenup/server/openapi/OpenApiDocument.kt`:

```kotlin
package com.calypsan.listenup.server.openapi

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * DECISION (2026-06-17): code-first OpenAPI. Each REST endpoint is declared once in
 * [authOperations]; request/response schemas are derived from the `@Serializable`
 * DTO descriptors by [schemaFor]. To document a new endpoint, add an [ApiOperation]
 * here — the served document and the drift-guard test pick it up automatically.
 * (If a code-first library is later adopted, replace this assembly while keeping the
 * /api/openapi.json + /api/docs contract that OpenApiAuthDriftTest pins.)
 */
data class ApiOperation(
    val path: String,
    val method: String,
    val summary: String,
    val requestType: SerialDescriptor? = null,
    val responseType: SerialDescriptor,
    val authenticated: Boolean,
)

@OptIn(ExperimentalSerializationApi::class)
private inline fun <reified T> descriptorOf(): SerialDescriptor = serializer<T>().descriptor

@OptIn(ExperimentalSerializationApi::class)
val authOperations: List<ApiOperation> =
    listOf(
        ApiOperation("/api/v1/auth/login", "post", "Authenticate and start a session",
            descriptorOf<LoginRequest>(), descriptorOf<AuthSession>(), authenticated = false),
        ApiOperation("/api/v1/auth/register", "post", "Register a new user",
            descriptorOf<RegisterRequest>(), descriptorOf<RegisterResult>(), authenticated = false),
        ApiOperation("/api/v1/auth/setup", "post", "Create the first (root) user",
            descriptorOf<RegisterRequest>(), descriptorOf<AuthSession>(), authenticated = false),
        ApiOperation("/api/v1/auth/refresh", "post", "Exchange a refresh token for a new session",
            descriptorOf<RefreshRequest>(), descriptorOf<AuthSession>(), authenticated = false),
        ApiOperation("/api/v1/auth/logout", "post", "Revoke the current session",
            null, descriptorOf<User>(), authenticated = true),
        ApiOperation("/api/v1/auth/logout/all", "post", "Revoke all of the user's sessions",
            null, descriptorOf<User>(), authenticated = true),
        ApiOperation("/api/v1/auth/current-user", "get", "Get the authenticated user",
            null, descriptorOf<User>(), authenticated = true),
        ApiOperation("/api/v1/auth/sessions", "get", "List the user's active sessions",
            null, descriptorOf<SessionSummary>(), authenticated = true),
        ApiOperation("/api/v1/auth/sessions/{id}", "delete", "Revoke a specific session",
            null, descriptorOf<User>(), authenticated = true),
    )

/** Minimal JSON-Schema for a kotlinx.serialization descriptor (objects, lists, primitives). */
@OptIn(ExperimentalSerializationApi::class)
fun schemaFor(descriptor: SerialDescriptor): JsonObject =
    when (descriptor.kind) {
        is StructureKind.LIST ->
            buildJsonObject {
                put("type", "array")
                put("items", schemaFor(descriptor.getElementDescriptor(0)))
            }
        is StructureKind.CLASS, is StructureKind.OBJECT ->
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        for (i in 0 until descriptor.elementsCount) {
                            put(descriptor.getElementName(i), schemaFor(descriptor.getElementDescriptor(i)))
                        }
                    },
                )
            }
        SerialKind.ENUM ->
            buildJsonObject {
                put("type", "string")
                put(
                    "enum",
                    buildJsonArray { for (i in 0 until descriptor.elementsCount) add(JsonPrimitive(descriptor.getElementName(i))) },
                )
            }
        else -> buildJsonObject { put("type", primitiveType(descriptor)) }
    }

@OptIn(ExperimentalSerializationApi::class)
private fun primitiveType(descriptor: SerialDescriptor): String =
    when (descriptor.serialName.substringAfterLast('.')) {
        "Int", "Long", "Short", "Byte" -> "integer"
        "Float", "Double" -> "number"
        "Boolean" -> "boolean"
        else -> "string"
    }

/** Assemble the full OpenAPI 3.0 document from [operations]. */
fun buildOpenApiDocument(operations: List<ApiOperation> = authOperations): JsonObject =
    buildJsonObject {
        put("openapi", "3.0.3")
        put(
            "info",
            buildJsonObject {
                put("title", "ListenUp API")
                put("version", "v1")
            },
        )
        put("paths", buildPaths(operations))
    }

private fun buildPaths(operations: List<ApiOperation>): JsonObject =
    buildJsonObject {
        operations.groupBy { it.path }.forEach { (path, ops) ->
            put(
                path,
                buildJsonObject {
                    ops.forEach { op -> put(op.method, buildOperation(op)) }
                },
            )
        }
    }

private fun buildOperation(op: ApiOperation): JsonObject =
    buildJsonObject {
        put("summary", op.summary)
        if (op.requestType != null) {
            put("requestBody", jsonContent(schemaFor(op.requestType)))
        }
        put(
            "responses",
            buildJsonObject {
                put(
                    "200",
                    buildJsonObject {
                        put("description", "Success")
                        put("content", contentSchema(schemaFor(op.responseType)))
                    },
                )
            },
        )
        if (op.authenticated) {
            put("security", buildJsonArray { add(buildJsonObject { put("bearerAuth", JsonArray(emptyList())) }) })
        }
    }

private fun jsonContent(schema: JsonObject): JsonObject =
    buildJsonObject {
        put("required", true)
        put("content", contentSchema(schema))
    }

private fun contentSchema(schema: JsonObject): JsonObject =
    buildJsonObject {
        put("application/json", buildJsonObject { put("schema", schema) })
    }
```

> NOTE on `AppResult<T>` envelope: auth endpoints wrap responses in `AppResult<T>`. For Phase 1A the response schema documents the success payload type (`T`) for clarity; if you adopt the library route, mirror this choice. Documenting the full `AppResult` polymorphic envelope is a deliberate later refinement, not required to pass the drift-guard test.

- [ ] **Step 5: Serve the document + Swagger UI**

Create `server/src/main/kotlin/com/calypsan/listenup/server/openapi/OpenApiRoutes.kt`:

```kotlin
package com.calypsan.listenup.server.openapi

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Serve the generated OpenAPI document at /api/openapi.json and a Swagger UI page at
 * /api/docs that loads it. Swagger UI is served as a tiny CDN-backed HTML host so we
 * need no bundled UI assets; the spec itself is generated in-process (see OpenApiDocument).
 */
fun Route.openApiRoutes() {
    get("/api/openapi.json") {
        call.respondText(buildOpenApiDocument().toString(), ContentType.Application.Json)
    }
    get("/api/docs") {
        call.respondText(SWAGGER_UI_HTML, ContentType.Text.Html)
    }
}

private val SWAGGER_UI_HTML =
    """
    <!doctype html>
    <html>
      <head>
        <title>ListenUp API — Swagger UI</title>
        <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist/swagger-ui.css">
      </head>
      <body>
        <div id="swagger"></div>
        <script src="https://unpkg.com/swagger-ui-dist/swagger-ui-bundle.js"></script>
        <script>
          window.ui = SwaggerUIBundle({ url: '/api/openapi.json', dom_id: '#swagger' });
        </script>
      </body>
    </html>
    """.trimIndent()
```

> If you adopted a library in Step 3, replace this file's bodies with the library's `openAPI(...)`/`swaggerUI(...)` route installers, keeping the same two URLs so `OpenApiAuthDriftTest` still passes.

- [ ] **Step 6: Mount the OpenAPI routes**

In `Application.kt`, add the import:

```kotlin
import com.calypsan.listenup.server.openapi.openApiRoutes
```

Inside the `routing { ... }` block of `installAppRoutes` (alongside `healthRoutes()`, `instanceRoutes(...)`), add:

```kotlin
        openApiRoutes()
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `gradle :server:test --tests "com.calypsan.listenup.server.openapi.OpenApiAuthDriftTest"`
Expected: PASS (both tests).

- [ ] **Step 8: Commit**

```bash
git add server/src/main/kotlin/com/calypsan/listenup/server/openapi server/src/main/kotlin/com/calypsan/listenup/server/Application.kt server/src/test/kotlin/com/calypsan/listenup/server/openapi
git commit -m "📝 feat(server): code-first OpenAPI document + Swagger UI for auth surface

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: App-shell HTML + htmx asset + catalog deps

**Files:**
- Modify: `gradle/libs.versions.toml`
- Create: `web/src/main/kotlin/com/calypsan/listenup/web/html/AppShell.kt`
- Modify: `web/src/main/kotlin/com/calypsan/listenup/web/routes/ShellRoutes.kt`
- Create: `web/src/main/resources/web/htmx.min.js`
- Test: `server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt` (extend)

- [ ] **Step 1: Extend the failing test**

Replace the body of the existing `WebShellRoutesTest` test with assertions for the real shell:

```kotlin
        test("GET / serves the web shell HTML with asset links") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val response = client.get("/")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "<!DOCTYPE html>"
                html shouldContain "ListenUp"
                html shouldContain "/assets/htmx.min.js"
                html shouldContain "/assets/app.css"
            }
        }

        test("GET /assets/htmx.min.js serves the vendored htmx runtime") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                client.get("/assets/htmx.min.js").status shouldBe HttpStatusCode.OK
            }
        }
```

- [ ] **Step 2: Run to verify it fails**

Run: `gradle :server:test --tests "com.calypsan.listenup.server.WebShellRoutesTest"`
Expected: FAIL — the placeholder HTML lacks `<!DOCTYPE html>`/asset links; `/assets/htmx.min.js` 404.

- [ ] **Step 3: Add catalog entries** in `gradle/libs.versions.toml`

In `[versions]` add:

```toml
kotlinx-html = "0.11.0"
```

In `[libraries]` add (mirroring the existing `version.ref = "ktor"` style):

```toml
ktor-server-html-builder = { module = "io.ktor:ktor-server-html-builder", version.ref = "ktor" }
kotlinx-html = { module = "org.jetbrains.kotlinx:kotlinx-html", version.ref = "kotlinx-html" }
```

> Verify `kotlinx-html` 0.11.0 resolves; if not, bump to the latest published version and keep going.

- [ ] **Step 4: Create the app-shell** `web/src/main/kotlin/com/calypsan/listenup/web/html/AppShell.kt`

```kotlin
package com.calypsan.listenup.web.html

import kotlinx.html.HTML
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.script
import kotlinx.html.title

/**
 * The base HTML document every web page is rendered into. Pages supply their body
 * content via [content]; the shell provides the head (Tailwind stylesheet, htmx runtime,
 * CSRF meta — CSRF wired in Phase 1B) and a consistent outer layout.
 */
fun HTML.appShell(
    pageTitle: String = "ListenUp",
    content: kotlinx.html.MAIN.() -> Unit,
) {
    lang = "en"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title { +pageTitle }
        link(rel = "stylesheet", href = "/assets/app.css")
        script(src = "/assets/htmx.min.js") {}
    }
    body {
        main {
            content()
        }
    }
}
```

- [ ] **Step 5: Render the shell from the route** — replace `web/src/main/kotlin/com/calypsan/listenup/web/routes/ShellRoutes.kt`

```kotlin
package com.calypsan.listenup.web.routes

import com.calypsan.listenup.web.html.appShell
import io.ktor.server.html.respondHtml
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.h1
import kotlinx.html.p

/** Landing route — renders the app shell. Authenticated content arrives in Phase 1B/2. */
fun Route.shellRoutes() {
    get("/") {
        call.respondHtml {
            appShell(pageTitle = "ListenUp") {
                h1 { +"ListenUp" }
                p { +"Your library, in the browser." }
            }
        }
    }
}
```

- [ ] **Step 6: Vendor htmx** — download the runtime into `web/src/main/resources/web/htmx.min.js`

Run:

```bash
mkdir -p web/src/main/resources/web
curl -fsSL https://unpkg.com/htmx.org/dist/htmx.min.js -o web/src/main/resources/web/htmx.min.js
test -s web/src/main/resources/web/htmx.min.js && echo "htmx vendored"
```

- [ ] **Step 7: Run to verify it passes**

Run: `gradle :server:test --tests "com.calypsan.listenup.server.WebShellRoutesTest"`
Expected: PASS (both tests). (`/assets/app.css` 404 does not fail these tests — they assert the link is present in HTML, not that the CSS resolves. The CSS is produced in Task 4.)

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml web/src/main/kotlin web/src/main/resources/web/htmx.min.js server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt
git commit -m "🎨 feat(web): kotlinx.html app shell + vendored htmx runtime

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Tailwind toolchain → generated stylesheet

**Files:**
- Create: `web/tailwind.config.js`
- Create: `web/src/main/tailwind/input.css`
- Modify: `web/build.gradle.kts` (add the generation task + wire generated resources)
- Test: `server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt` (extend)

- [ ] **Step 1: Write the failing test** — add to `WebShellRoutesTest`:

```kotlin
        test("GET /assets/app.css serves generated Tailwind utilities") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val response = client.get("/assets/app.css")
                response.status shouldBe HttpStatusCode.OK
                // The shell uses `mx-auto`; Tailwind must have emitted it.
                response.bodyAsText() shouldContain ".mx-auto"
            }
        }
```

Also update `AppShell.kt`'s `main { }` to carry a class so Tailwind has something to scan and emit — change the `main {` call to:

```kotlin
        main(classes = "mx-auto") {
            content()
        }
```

- [ ] **Step 2: Run to verify it fails**

Run: `gradle :server:test --tests "com.calypsan.listenup.server.WebShellRoutesTest"`
Expected: FAIL — `/assets/app.css` 404 (no generated CSS yet).

- [ ] **Step 3: Tailwind config** `web/tailwind.config.js`

```js
/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["./src/main/kotlin/**/*.kt"],
  theme: { extend: {} },
  plugins: [],
};
```

- [ ] **Step 4: Tailwind input** `web/src/main/tailwind/input.css`

```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```

- [ ] **Step 5: Add the generation task to `web/build.gradle.kts`**

Append:

```kotlin
// Tailwind: scan :web .kt files for class names, emit one stylesheet into generated
// resources so it ships on the classpath at web/app.css and is served at /assets/app.css.
// Requires the Tailwind standalone CLI. Resolve it from the TAILWIND_CLI env var, else
// expect `tailwindcss` on PATH. (Standalone binary: github.com/tailwindlabs/tailwindcss/releases)
val tailwindOutDir = layout.buildDirectory.dir("generated-resources/tailwind/web")

val tailwindGenerate by tasks.registering(Exec::class) {
    group = "web"
    description = "Generate the Tailwind stylesheet for the web UI."
    inputs.dir("src/main/kotlin")
    inputs.file("tailwind.config.js")
    inputs.file("src/main/tailwind/input.css")
    outputs.dir(tailwindOutDir)
    doFirst { tailwindOutDir.get().asFile.mkdirs() }
    val cli = System.getenv("TAILWIND_CLI") ?: "tailwindcss"
    commandLine(
        cli,
        "-c", "tailwind.config.js",
        "-i", "src/main/tailwind/input.css",
        "-o", "${tailwindOutDir.get().asFile}/app.css",
        "--minify",
    )
}

sourceSets.main {
    resources.srcDir(tailwindOutDir)
}

tasks.named("processResources") {
    dependsOn(tailwindGenerate)
}
```

- [ ] **Step 6: Run to verify it passes**

Ensure the Tailwind CLI is available (PATH or `TAILWIND_CLI`), then:

Run: `gradle :server:test --tests "com.calypsan.listenup.server.WebShellRoutesTest"`
Expected: PASS (all four tests). The `:web:tailwindGenerate` task runs transitively via `processResources`; `app.css` contains `.mx-auto`.

> If the standalone CLI is unavailable in the execution environment, set `TAILWIND_CLI` to the downloaded binary path. Do not substitute a hand-written CSS file — the toolchain is the deliverable.

- [ ] **Step 7: Commit**

```bash
git add web/tailwind.config.js web/src/main/tailwind/input.css web/build.gradle.kts web/src/main/kotlin/com/calypsan/listenup/web/html/AppShell.kt server/src/test/kotlin/com/calypsan/listenup/server/WebShellRoutesTest.kt
git commit -m "🎨 build(web): Tailwind CLI generates the web stylesheet

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Konsist — enforce the BFF boundary

**Files:**
- Create: `web/src/test/kotlin/com/calypsan/listenup/web/konsist/WebBoundaryKonsistTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.calypsan.listenup.web.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.FunSpec

/**
 * Structural BFF boundary: :web must reach the domain only through the REST API, never
 * by importing :server. This keeps "the web UI consumes the API" a compile-time fact.
 */
class WebBoundaryKonsistTest :
    FunSpec({
        test(":web has no import from the :server module") {
            Konsist
                .scopeFromModule("web")
                .files
                .assertTrue { file ->
                    file.imports.none { it.name.startsWith("com.calypsan.listenup.server") }
                }
        }
    })
```

- [ ] **Step 2: Run to verify it passes immediately**

Run: `gradle :web:test --tests "com.calypsan.listenup.web.konsist.WebBoundaryKonsistTest"`
Expected: PASS (no `:server` imports exist yet). This test is a *regression guard*, so it is green from the start — its value is failing the build if a future change introduces a `:server` import.

> If `scopeFromModule("web")` finds no files or errors on module name, switch to `Konsist.scopeFromProject(moduleName = "web")` or `scopeFromDirectory("web/src/main")` — match whichever Konsist scoping the repo's existing `:server` Konsist tests use (see `server/src/test/.../konsist`).

- [ ] **Step 3: Verify the guard actually catches a violation (temporary check)**

Temporarily add `import com.calypsan.listenup.server.Application` to `WebUi.kt`, re-run the test, confirm it FAILS, then remove the import and confirm it PASSES again. This proves the guard works.

- [ ] **Step 4: Commit**

```bash
git add web/src/test/kotlin/com/calypsan/listenup/web/konsist/WebBoundaryKonsistTest.kt
git commit -m "✅ test(web): Konsist guard — :web must not import :server

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final verification

- [ ] **Run the full module test suites**

Run: `gradle :web:test :server:test`
Expected: all PASS.

- [ ] **Boot the server and eyeball it (optional manual check)**

Run: `gradle :server:run` then visit `http://127.0.0.1:8080/` (styled placeholder) and `http://127.0.0.1:8080/api/docs` (Swagger UI listing the auth endpoints). Stop with Ctrl-C.

---

## Self-Review (completed by plan author)

**Spec coverage (Phase 1 §2 module, §5 OpenAPI; architecture §3.3 boundary, §4.2/§4.4 docs+Tailwind):**
- `:web` module depends on `:contract` only, mounted by `:server` → Tasks 1 & 5. ✓
- Code-first OpenAPI generated + Swagger UI + drift-guard → Task 2. ✓
- App shell (kotlinx.html) + htmx asset → Task 3. ✓
- Tailwind via standalone CLI scanning `.kt` → Task 4. ✓
- Konsist boundary rule → Task 5. ✓
- *Deferred to Plan 1B (by design):* loopback client wiring, browser session/CSRF, auth screens, the `WebUiConfig.loopbackBaseUrl` consumer.

**Placeholder scan:** No TBD/TODO. The Task 2 spike is intentionally outcome-driven but ships a fully-specified fallback (Steps 4–5), so no step lacks runnable content. The cross-task ordering note in Task 1 Step 4 is a real execution note, not a placeholder.

**Type consistency:** `installWebUi(WebUiConfig)` (Task 1) matches the call site (Task 1 Step 8). `appShell(pageTitle, content)` defined in Task 3 Step 4, used in Task 3 Step 5 and amended in Task 4 Step 1. `buildOpenApiDocument()` (Task 2 Step 4) used in Task 2 Step 5 and asserted in Step 1. `shellRoutes()` defined Task 1, replaced Task 3. Consistent.

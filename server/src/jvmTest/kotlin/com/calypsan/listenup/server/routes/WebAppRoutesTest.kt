package com.calypsan.listenup.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.io.files.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

/**
 * The browser store keeps its database in OPFS, which needs `SharedArrayBuffer`, which the
 * browser exposes only under cross-origin isolation, which requires COOP/COEP response headers
 * on the document that loads it.
 *
 * That makes the headers a correctness requirement of the *server*, not a dev-server
 * convenience: serve the web app without them and the client silently loses its database. The
 * failure is silent and remote, so it is pinned here rather than left to be discovered in a
 * browser.
 */
class WebAppRoutesTest :
    FunSpec({
        fun webRoot(): Path {
            val root = createTempDirectory("webapp")
            root.resolve("index.html").writeText("<!doctype html><title>ListenUp</title>")
            root.resolve("assets").createDirectories()
            root.resolve("assets/app.js").writeText("export const x = 1")
            // A file the web root must never be able to reach, for the traversal case below.
            root.parent.resolve("secret.txt").writeText("TOP SECRET")
            return Path(root.toString())
        }

        test("the web app document is served") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root) } }

                val response = client.get("/")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "ListenUp"
            }
        }

        test("the web app document is cross-origin isolated") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root) } }

                val response = client.get("/")

                response.headers["Cross-Origin-Opener-Policy"] shouldBe "same-origin"
                response.headers["Cross-Origin-Embedder-Policy"] shouldBe "require-corp"
            }
        }

        test("assets carry the isolation headers too") {
            // COEP require-corp governs subresources: the wasm binary and the SQLite worker are
            // fetched, not navigated to, so headers on the document alone are not enough.
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root) } }

                val response = client.get("/assets/app.js")

                response.status shouldBe HttpStatusCode.OK
                response.headers["Cross-Origin-Embedder-Policy"] shouldBe "require-corp"
            }
        }

        test("an unknown path falls back to the document, so client routing survives a reload") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root) } }

                val response = client.get("/library/some-book")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "ListenUp"
                // The fallback is the shell, whatever the URL looked like — so it must revalidate
                // like a document, not be frozen for a year like a content-hashed asset.
                response.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
            }
        }

        test("a missing asset falls back to the shell and is NOT cached for a year") {
            // The reference check in WebAppRoutes, and the only thing that pins it. /assets/ is the
            // immutable prefix, but a MISS there is served the shell, whose URL never changes —
            // freezing that for a year strands the visitor on a stale build at that URL forever.
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root) } }

                val response = client.get("/assets/gone.js")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "ListenUp"
                response.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
            }
        }

        test("a hashed asset is cached for a year") {
            // Vite content-hashes everything under assets/, so the URL changes whenever the bytes
            // do — which is exactly the precondition `immutable` asks for.
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root) } }

                val response = client.get("/assets/app.js")

                response.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=31536000, immutable"
            }
        }

        test("the document is never cached") {
            // `no-cache` means revalidate, not don't-store: the shell's URL never changes, so a
            // deploy would otherwise stay invisible until the cache expired.
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root) } }

                val response = client.get("/")

                response.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
            }
        }

        test("every response carries a content ETag") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root) } }

                val document = client.get("/").headers[HttpHeaders.ETag]
                val asset = client.get("/assets/app.js").headers[HttpHeaders.ETag]

                document.shouldNotBeNull().shouldNotBeBlank()
                asset.shouldNotBeNull().shouldNotBeBlank()
                document shouldNotBe asset
            }
        }

        test("a matching If-None-Match is answered 304 with no body") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root) } }

                val etag = client.get("/assets/app.js").headers[HttpHeaders.ETag].shouldNotBeNull()
                val revalidated = client.get("/assets/app.js") { header(HttpHeaders.IfNoneMatch, etag) }

                revalidated.status shouldBe HttpStatusCode.NotModified
                revalidated.bodyAsText().isEmpty() shouldBe true
            }
        }

        test("a traversal attempt cannot escape the web root") {
            // This route hand-rolls static serving (Ktor's staticFiles is JVM-only and
            // production is native), so it does not inherit Ktor's traversal protection and
            // has to prove its own.
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root) } }

                val response = client.get("/../secret.txt")

                response.bodyAsText() shouldNotContain "TOP SECRET"
            }
        }

        test("no route is mounted when no web root is configured") {
            // A server built without the bundled web app must not answer / at all, rather than
            // answering with an empty or broken shell.
            testApplication {
                application { routing { webAppRoutes(null) } }

                client.get("/").status shouldBe HttpStatusCode.NotFound
            }
        }
    })

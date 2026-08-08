package com.calypsan.listenup.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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

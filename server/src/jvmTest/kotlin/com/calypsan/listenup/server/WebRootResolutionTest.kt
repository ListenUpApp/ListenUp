package com.calypsan.listenup.server

import io.kotest.core.spec.style.FunSpec
import io.ktor.client.request.get
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.io.path.createTempDirectory

/**
 * Pins the promise that a server built without the web bundle serves no web client.
 *
 * This is a **release-safety** contract, not a routing detail. `webAppRoutes(null)` mounts nothing
 * — that much is already covered by `WebAppRoutesTest` — but nothing pinned the layer that decides
 * whether `null` is what it gets, and that is the layer one `application.conf` default away from
 * silently shipping a half-built web client to every self-hoster on the next server image. The
 * release Dockerfile builds no web assets and sets no `WEB_ROOT`, so this resolving to null is the
 * whole reason `/` is a 404 there.
 *
 * The "points at nothing" cases matter as much as the absent one: a stale or mistyped path must
 * fail closed to no-web-client, never to a partially-served shell.
 */
class WebRootResolutionTest :
    FunSpec({

        /** Resolves `web.root` against a config carrying exactly [entries]. */
        suspend fun resolveWith(vararg entries: Pair<String, String>): Path? {
            var resolved: Path? = null
            testApplication {
                environment {
                    config =
                        MapApplicationConfig().apply {
                            entries.forEach { (key, value) -> put(key, value) }
                        }
                }
                application { resolved = resolveWebRoot() }
                // The client call is what forces the application module to run.
                client.get("/")
            }
            return resolved
        }

        test("no web.root property means no web client — the shape every release ships") {
            resolveWith().shouldBeNull()
        }

        test("a blank web.root means no web client") {
            resolveWith("web.root" to "   ").shouldBeNull()
        }

        test("a web.root pointing at nothing fails closed rather than serving a partial shell") {
            resolveWith("web.root" to "/nonexistent/listenup/web/dist").shouldBeNull()
        }

        test("a web.root pointing at a file rather than a directory fails closed") {
            val file = createTempDirectory("listenup-webroot").resolve("index.html")
            file.toFile().writeText("<!doctype html>")

            resolveWith("web.root" to file.toString()).shouldBeNull()
        }

        test("a web.root pointing at a real directory is honoured — opting in still works") {
            val dir = createTempDirectory("listenup-webroot")

            val resolved = resolveWith("web.root" to dir.toString())

            resolved.shouldNotBeNullAnd { SystemFileSystem.metadataOrNull(it)?.isDirectory shouldBe true }
        }
    })

/** Asserts non-null and runs [block] on the value — keeps the opt-in case a single assertion. */
private inline fun <T : Any> T?.shouldNotBeNullAnd(block: (T) -> Unit) {
    checkNotNull(this) { "expected a resolved web root, got null" }
    block(this)
}

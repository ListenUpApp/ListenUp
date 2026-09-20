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
 * Pins how the server decides whether to serve a web client at all.
 *
 * This is a **release-safety** contract, not a routing detail. `webAppRoutes(null)` mounts nothing
 * — `WebAppRoutesTest` covers that — but nothing pinned the layer deciding whether `null` is what
 * it gets.
 *
 * ⛔ **The release shape inverted.** This file used to open by pinning "no `web.root` means no web
 * client — the shape every release ships", and that was true: `release.yml` built no bundle,
 * `Dockerfile.native` copied none, and `LISTENUP_WEB_ROOT` was exempted as JVM-only so setting it
 * on the native image did nothing whatsoever. The image now bakes the bundle in and sets that
 * variable itself, so `/` serves the client on a default `docker run`.
 *
 * Which makes the fail-closed cases matter **more**, not less. The default is still empty, so
 * every one of them is a path a shipped image can take if its bundle goes missing or its
 * `LISTENUP_WEB_ROOT` is mistyped — and each must land on no-web-client rather than on a
 * partially-served shell that looks like a broken app instead of an absent one.
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

        test("no web.root property means no web client — the default, not the release shape") {
            // Still the behaviour, no longer the thing a release does: the image sets the variable.
            resolveWith().shouldBeNull()
        }

        test("the native binary honours LISTENUP_WEB_ROOT, because the shipped image is native") {
            // ⛔ The bug this pins is invisible from the JVM. HOCON is JVM-only, so the native
            // server builds its config from SERVER_CONFIG_DEFAULTS alone — a knob missing there is
            // silently dead on the ONLY build that ships. `web.root` sat exempted in exactly that
            // state, which is why "just set LISTENUP_WEB_ROOT on the image" could never have worked.
            val entry = SERVER_CONFIG_DEFAULTS.singleOrNull { it.key == "web.root" }

            checkNotNull(entry) { "web.root is undeclared, so the native image cannot serve a web client" }
            entry.envVar shouldBe "LISTENUP_WEB_ROOT"
            // Empty, so a JVM dev run and an image without the bundle both stay off.
            entry.default shouldBe ""
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

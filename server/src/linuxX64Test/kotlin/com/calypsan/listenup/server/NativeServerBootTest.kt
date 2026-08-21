package com.calypsan.listenup.server

import com.calypsan.listenup.server.io.readEnv
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.client.statement.HttpResponse
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.setenv
import platform.posix.unsetenv

private const val LISTENUP_HOME_ENV = "LISTENUP_HOME"

/**
 * Hard ceiling on boot-and-serve. Generous next to the ~4 minutes the whole native lane takes,
 * but far below CI's 10-minute step cap — the point is that a wedge fails as *this named test*
 * instead of consuming the step and reporting only "the action has timed out", which attributes
 * the failure to nothing and has already been mis-blamed on an unrelated PR once.
 */
private val BOOT_TIMEOUT = 90.seconds

/** Per-request ceiling, so a single unlucky request cannot absorb the whole [BOOT_TIMEOUT]. */
private const val REQUEST_TIMEOUT_MS = 5_000L

/**
 * Boots the REAL [Application.module] on a Kotlin/Native `embeddedServer(CIO)`
 * and serves `GET /healthz`. A successful boot exercises the entire native stack at once — the native
 * SQLite driver (schema migrations), native crypto (the auto-generated JWT/refresh secrets), file I/O
 * (`$LISTENUP_HOME/secrets.properties`), the full Koin graph, and the request pipeline — so this is
 * where any remaining cinterop/glibc gap surfaces. The data home is a distinct hidden dir under
 * `$HOME`, so the boot never writes into the worktree.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeServerBootTest :
    FunSpec({
        test("native server boots and serves healthz") {
            // Isolate the test data home under $HOME (a distinct hidden dir — never the real
            // ~/ListenUp), so the boot never writes into the worktree. Falls back to a CWD-relative
            // dir only if $HOME is somehow unset in the test runner.
            val home =
                readEnv("HOME")?.takeIf { it.isNotBlank() }?.let { "$it/.lu-native-boot-test" }
                    ?: "lu-native-boot-test"
            // LISTENUP_HOME is process-global and the native lane runs every spec in ONE process, so
            // this has to be put back below — the finally deletes the directory, and a later spec that
            // resolves its data home from the environment would otherwise silently boot against this
            // deleted path instead of its own.
            val originalListenupHome = readEnv(LISTENUP_HOME_ENV)
            setenv(LISTENUP_HOME_ENV, home, 1)
            val server =
                embeddedServer(
                    factory = CIO,
                    environment = applicationEnvironment { config = defaultServerConfig() },
                    configure = { connectors.add(EngineConnectorBuilder().apply { port = 0 }) },
                ) { module() }
            server.start(wait = false)
            // Without HttpTimeout a request that is never accepted waits forever: the plugin is not
            // installed by default, and neither the client nor `resolvedConnectors()` is otherwise bounded.
            val client =
                HttpClient(ClientCIO) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = REQUEST_TIMEOUT_MS
                        connectTimeoutMillis = REQUEST_TIMEOUT_MS
                        socketTimeoutMillis = REQUEST_TIMEOUT_MS
                    }
                }
            try {
                withTimeout(BOOT_TIMEOUT) {
                    val port =
                        server.engine
                            .resolvedConnectors()
                            .first()
                            .port
                    val response = awaitServing(client, port)
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldContain "ok"
                }
            } finally {
                client.close()
                server.stop(0, 0)
                runCatching { deleteRecursivelyIfPresent(Path(home)) }
                if (originalListenupHome != null) {
                    setenv(LISTENUP_HOME_ENV, originalListenupHome, 1)
                } else {
                    unsetenv(LISTENUP_HOME_ENV)
                }
            }
        }
    })

/**
 * Polls `GET /healthz` until the server answers, returning the first response that arrives.
 *
 * `start(wait = false)` plus a resolved connector means the listening socket is *bound*, not that the
 * accept loop is already serving — and on Kotlin/Native the client and server engines share a
 * constrained dispatcher, so on a small CI runner the first connect can arrive before anything is
 * ready to take it. One request is therefore not a fair test of whether boot succeeded; retrying a
 * short-timeout request is. Bounded from the outside by [BOOT_TIMEOUT], so this cannot spin forever.
 */
private suspend fun awaitServing(
    client: HttpClient,
    port: Int,
): HttpResponse {
    while (true) {
        val attempt = runCatching { client.get("http://127.0.0.1:$port/healthz") }
        attempt.getOrNull()?.let { return it }
        delay(100)
    }
}

private fun deleteRecursivelyIfPresent(path: Path) {
    val meta = SystemFileSystem.metadataOrNull(path) ?: return
    if (meta.isDirectory) SystemFileSystem.list(path).forEach { deleteRecursivelyIfPresent(it) }
    SystemFileSystem.delete(path, mustExist = false)
}

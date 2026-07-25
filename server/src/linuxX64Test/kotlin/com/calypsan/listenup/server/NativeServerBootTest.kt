package com.calypsan.listenup.server

import com.calypsan.listenup.server.io.readEnv
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.setenv
import platform.posix.unsetenv

private const val LISTENUP_HOME_ENV = "LISTENUP_HOME"

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
            val client = HttpClient(ClientCIO)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val response = client.get("http://127.0.0.1:$port/healthz")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "ok"
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

private fun deleteRecursivelyIfPresent(path: Path) {
    val meta = SystemFileSystem.metadataOrNull(path) ?: return
    if (meta.isDirectory) SystemFileSystem.list(path).forEach { deleteRecursivelyIfPresent(it) }
    SystemFileSystem.delete(path, mustExist = false)
}

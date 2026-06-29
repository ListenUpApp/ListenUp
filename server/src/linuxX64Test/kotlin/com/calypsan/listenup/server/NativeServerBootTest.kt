package com.calypsan.listenup.server

import com.calypsan.listenup.server.io.readEnv
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
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.setenv
import kotlin.test.Test

/**
 * Boots the REAL [Application.module] on a Kotlin/Native `embeddedServer(CIO)`
 * and serves `GET /healthz`. A successful boot exercises the entire native stack at once — the native
 * SQLite driver (schema migrations), native crypto (the auto-generated JWT/refresh secrets), file I/O
 * (`$LISTENUP_HOME/secrets.properties`), the full Koin graph, and the request pipeline — so this is
 * where any remaining cinterop/glibc gap surfaces. The data home is a distinct hidden dir under
 * `$HOME` (the native test runner has no usable temp dir), so the boot never writes into the worktree.
 */
class NativeServerBootTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun nativeServerBootsAndServesHealthz(): Unit =
        runBlocking {
            // Isolate the test data home under $HOME (a distinct hidden dir — never the real
            // ~/ListenUp), so the boot never writes into the worktree. Falls back to a CWD-relative
            // dir only if $HOME is somehow unset in the test runner.
            val home =
                readEnv("HOME")?.takeIf { it.isNotBlank() }?.let { "$it/.lu-native-boot-test" }
                    ?: "lu-native-boot-test"
            setenv("LISTENUP_HOME", home, 1)
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
            }
        }
}

private fun deleteRecursivelyIfPresent(path: Path) {
    val meta = SystemFileSystem.metadataOrNull(path) ?: return
    if (meta.isDirectory) SystemFileSystem.list(path).forEach { deleteRecursivelyIfPresent(it) }
    SystemFileSystem.delete(path, mustExist = false)
}

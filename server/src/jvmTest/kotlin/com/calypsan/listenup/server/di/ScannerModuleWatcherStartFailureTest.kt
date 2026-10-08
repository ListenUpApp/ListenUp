package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.logging.ListenUpLoggerFactory
import com.calypsan.listenup.server.scanner.WatcherSupervisorPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.slf4j.event.Level

/**
 * A folder watcher that fails to start disables watching for that folder, so the failure must
 * reach the server log. It used to be written to Koin's own logger — the `logger` in scope inside
 * a `single { }` block — which this server never configures, so the warning vanished.
 */
class ScannerModuleWatcherStartFailureTest :
    FunSpec({

        test("a watcher that fails to start logs a warning to the server log") {
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val koin =
                koinApplication {
                    modules(
                        scannerModule(applicationScope = appScope),
                        module { single { SelfWriteRegistry(clock = { 0L }) } },
                    )
                }.koin
            val supervisor = koin.get<WatcherSupervisorPort>()
            val capture = ListenUpLoggerFactory.installTestCapture()
            try {
                runBlocking {
                    supervisor.mount(
                        LibraryId("lib-1"),
                        LibraryFolderRef(id = FolderId("folder-1"), rootPath = "/nonexistent/listenup-watch-root"),
                    ) { _, _ -> }

                    val deadline = System.currentTimeMillis() + 5_000
                    while (
                        capture.events.none { it.message.startsWith("FolderWatcher failed to start") } &&
                        System.currentTimeMillis() < deadline
                    ) {
                        delay(10)
                    }
                }

                val warning =
                    capture.events
                        .firstOrNull { it.message.startsWith("FolderWatcher failed to start") }
                        .shouldNotBeNull()
                warning.level shouldBe Level.WARN
            } finally {
                ListenUpLoggerFactory.removeTestCapture()
                appScope.cancel()
            }
        }
    })

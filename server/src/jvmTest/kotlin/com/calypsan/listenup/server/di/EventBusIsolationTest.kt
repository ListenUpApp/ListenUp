package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.event.ScanEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path as IoPath
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.dsl.koinApplication

/**
 * Regression guard for the Koin type-erasure trap.
 *
 * `MutableSharedFlow<ScanEvent>`, `MutableSharedFlow<ImportEvent>`, and
 * `MutableSharedFlow<BackupEvent>` all erase to the same `MutableSharedFlow::class` runtime key.
 * Without distinct [named] qualifiers the three modules' progress buses collapse onto a single
 * shared instance (last registration wins), so scan / import / backup events intermingle and each
 * service's `observeProgress` throws a `ClassCastException` on the first foreign event it sees.
 *
 * The scanner module already qualifies its `MutableSharedFlow<ScanResult>` with `named("scanResultBus")`
 * for exactly this reason; this test pins the same discipline across the three cross-module event buses.
 */
class EventBusIsolationTest :
    FunSpec({
        test("scan, import, and backup progress buses are distinct instances") {
            val homeDir = Files.createTempDirectory("listenup-eventbus-")
            try {
                val app =
                    koinApplication {
                        modules(
                            scannerModule(CoroutineScope(SupervisorJob()), watchEnabled = false),
                            importModule(IoPath(homeDir.toString())),
                            backupModule(IoPath(homeDir.toString())),
                        )
                    }

                val scanBus = app.koin.get<MutableSharedFlow<ScanEvent>>(EventBusQualifiers.ScanEvents)
                val importBus = app.koin.get<MutableSharedFlow<ImportEvent>>(EventBusQualifiers.ImportEvents)
                val backupBus = app.koin.get<MutableSharedFlow<BackupEvent>>(EventBusQualifiers.BackupEvents)

                scanBus shouldNotBeSameInstanceAs importBus
                scanBus shouldNotBeSameInstanceAs backupBus
                importBus shouldNotBeSameInstanceAs backupBus

                app.close()
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }
    })

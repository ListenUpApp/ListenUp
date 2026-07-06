package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.server.api.BackupServiceImpl
import com.calypsan.listenup.server.api.ServerIdentity
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.backup.BackupArchive
import com.calypsan.listenup.server.backup.BackupPaths
import com.calypsan.listenup.server.backup.MaintenanceState
import com.calypsan.listenup.server.backup.RestoreOrchestrator
import com.calypsan.listenup.server.db.DatabaseHandle
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.mdns.InstanceIdentity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the backup/restore slice.
 *
 * Provides:
 *  - [BackupPaths] — filesystem locations under [homeDir].
 *  - [MutableSharedFlow]<[BackupEvent]> — progress event bus (shared with [RestoreOrchestrator]).
 *  - [MaintenanceState] — the single-flight gate used during restore.
 *  - [BackupArchive] — creates/opens/validates/extracts `.listenup.zip` archives.
 *  - [RestoreOrchestrator] — the live in-process restore state machine.
 *  - [BackupService] / [BackupServiceImpl] — admin-only RPC surface.
 *
 * The [InstanceIdentity] is resolved from the mDNS module (mdnsModule must be
 * installed in the same Koin container). [DatabaseHandle] is resolved from [authModule].
 * The [com.calypsan.listenup.server.sync.ChangeBus] singleton — resolved from `syncModule`
 * — is threaded into [RestoreOrchestrator] so a completed restore broadcasts a
 * re-baseline nudge to every connected device.
 */
fun backupModule(
    homeDir: Path,
    appVersion: String = ServerIdentity.VERSION,
): Module =
    module {
        single { BackupPaths(homeDir) }

        // Qualified by name because Koin keys on the erased KClass — an unqualified
        // MutableSharedFlow<BackupEvent> would collide with the scan/import event buses.
        single<MutableSharedFlow<BackupEvent>>(EventBusQualifiers.BackupEvents) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        }

        single { MaintenanceState() }

        single {
            BackupArchive(
                paths = get(),
                dbHandle = get<DatabaseHandle>(),
                serverId = {
                    // Reuse the persistent mDNS instance id from the server_settings KV table.
                    get<InstanceIdentity>().instanceId()
                },
                appVersion = appVersion,
                schemaVersion = {
                    get<DatabaseHandle>().currentSchemaVersion() ?: "0"
                },
                counts = {
                    val sql = get<ListenUpDatabase>()
                    suspendTransaction(sql) {
                        val bookCount =
                            sql.booksQueries
                                .countLive()
                                .executeAsOne()
                                .toInt()
                        val userCount =
                            sql.usersQueries
                                .countLive()
                                .executeAsOne()
                                .toInt()
                        bookCount to userCount
                    }
                },
            )
        }

        single {
            RestoreOrchestrator(
                paths = get(),
                archive = get(),
                dbHandle = get(),
                maintenance = get(),
                eventBus = get(EventBusQualifiers.BackupEvents),
                changeBus = get(),
            )
        }

        single<BackupService> {
            BackupServiceImpl(
                paths = get(),
                archive = get(),
                restoreOrchestrator = get(),
                eventBus = get(EventBusQualifiers.BackupEvents),
                principal = unscopedBackupPlaceholder("BackupService"),
            )
        }
    }

private fun unscopedBackupPlaceholder(serviceName: String): PrincipalProvider =
    PrincipalProvider { error("Unscoped $serviceName — call copyWith(PrincipalProvider) at the route") }

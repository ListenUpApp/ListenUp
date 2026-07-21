package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Backstops the cutovers: any new code referencing the deleted legacy sync classes — or the
 * retired SSE firehose transport — triggers Konsist failure. Mirror of [NoLegacyAppErrorRule]'s
 * shape.
 *
 * The SSE firehose ban covers the retired client-side names ([bannedImports]), the Ktor client
 * SSE plugin package (banned across all production source sets — no production client code may
 * re-grow an SSE transport; the one sanctioned SSE surface, the pre-auth registration-policy
 * stream, hand-rolls its reads through `SseConnection`, pinned by
 * [RawSseConstructionIsChannelOnlyRule], and never imports the plugin), and the Ktor SERVER SSE
 * plugin scoped to the server's `sync` package only: the firehose (`SyncStreamServiceImpl`) must
 * never re-grow an SSE route, while the sanctioned server SSE surfaces (scanner progress,
 * registration streams, healthz/smoke) live outside `sync/` and legitimately keep
 * `io.ktor.server.sse`.
 */
class NoLegacySyncReferencesRule :
    FunSpec({
        test("no production code references deleted legacy sync types") {
            // Banned outright in every production source set: the Ktor CLIENT SSE plugin —
            // the retired firehose transport cannot return under a new class name.
            val clientSsePrefix = "io.ktor.client.plugins.sse"
            // Banned only inside the server's sync package: the firehose must stay RPC-only,
            // but scanner/registration/smoke SSE routes outside sync/ are sanctioned.
            val serverSsePrefix = "io.ktor.server.sse"
            val bannedImports =
                setOf(
                    "com.calypsan.listenup.client.data.sync.SyncSseClient",
                    "com.calypsan.listenup.client.data.sync.SSEManager",
                    "com.calypsan.listenup.client.data.sync.SSEManagerContract",
                    "com.calypsan.listenup.client.data.sync.SSEEvent",
                    "com.calypsan.listenup.client.data.sync.SSEChannelMessage",
                    "com.calypsan.listenup.client.data.sync.SyncCoordinator",
                    "com.calypsan.listenup.client.data.sync.SyncManager",
                    "com.calypsan.listenup.client.data.sync.SyncManagerContract",
                    "com.calypsan.listenup.client.data.sync.SyncMutex",
                    "com.calypsan.listenup.client.data.sync.conflict.ConflictDetector",
                    "com.calypsan.listenup.client.data.sync.pull.ActiveSessionsPuller",
                    "com.calypsan.listenup.client.data.sync.pull.BookPuller",
                    "com.calypsan.listenup.client.data.sync.pull.BookRelationshipBundle",
                    "com.calypsan.listenup.client.data.sync.pull.BookRelationshipWriter",
                    "com.calypsan.listenup.client.data.sync.pull.ContributorPuller",
                    "com.calypsan.listenup.client.data.sync.pull.GenrePuller",
                    "com.calypsan.listenup.client.data.sync.pull.ListeningEventPuller",
                    "com.calypsan.listenup.client.data.sync.pull.ProgressPuller",
                    "com.calypsan.listenup.client.data.sync.pull.PullSyncOrchestrator",
                    "com.calypsan.listenup.client.data.sync.pull.Puller",
                    "com.calypsan.listenup.client.data.sync.pull.ReadingSessionPuller",
                    "com.calypsan.listenup.client.data.sync.pull.SeriesPuller",
                    "com.calypsan.listenup.client.data.sync.pull.ShelfPuller",
                    "com.calypsan.listenup.client.data.sync.pull.TagPuller",
                    "com.calypsan.listenup.client.data.sync.push.OperationExecutor",
                    "com.calypsan.listenup.client.data.sync.push.OperationExecutorContract",
                    "com.calypsan.listenup.client.data.sync.push.OperationHandler",
                    "com.calypsan.listenup.client.data.sync.push.OperationHandlers",
                    "com.calypsan.listenup.client.data.sync.push.OperationPayloads",
                    "com.calypsan.listenup.client.data.sync.push.PendingOperationRepository",
                    "com.calypsan.listenup.client.data.sync.push.PendingOperationRepositoryContract",
                    "com.calypsan.listenup.client.data.sync.push.PreferencesSyncObserver",
                    "com.calypsan.listenup.client.data.sync.push.PushSyncOrchestrator",
                    "com.calypsan.listenup.client.data.sync.push.ShelfPayloads",
                    "com.calypsan.listenup.client.data.sync.sse.BookRelationshipDaos",
                    "com.calypsan.listenup.client.data.sync.sse.SSEEventProcessor",
                    "com.calypsan.listenup.client.data.sync.sse.SSEExternalServices",
                    "com.calypsan.listenup.client.data.sync.EditableDomain",
                    "com.calypsan.listenup.client.data.sync.BookEdit",
                    "com.calypsan.listenup.client.data.sync.SeriesEdit",
                    "com.calypsan.listenup.client.data.sync.ContributorEdit",
                    "com.calypsan.listenup.client.data.sync.ProfileEdit",
                    "com.calypsan.listenup.client.data.sync.PreferencesEdit",
                )

            val offenders =
                productionScope()
                    .files
                    .flatMap { file ->
                        val inServerSync = "/server/src/" in file.path && "/sync/" in file.path
                        file.imports
                            .filter { import ->
                                import.name in bannedImports ||
                                    import.name.startsWith(clientSsePrefix) ||
                                    (inServerSync && import.name.startsWith(serverSsePrefix))
                            }.map { "${file.path} -> ${it.name}" }
                    }

            offenders.shouldBeEmpty()
        }
    })

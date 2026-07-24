import Foundation

/// Starts and keeps the offline-first sync engine live on iOS.
///
/// The Compose clients drive this from `AppShell`/`MainActivity` (`connectRealtime()` on auth +
/// every foreground). The native iOS shell has no equivalent, so without this the SSE firehose
/// and initial pull never start — the library stays empty until a manual pull-to-refresh, and
/// server-side additions never arrive live. `SyncEngine.start()` is single-flight, so activating
/// on both first-auth and every foreground is safe and idempotent.
///
/// Takes the two side effects as injected async closures so the wiring is unit-testable without
/// faking the large `SyncRepository` protocol. Both run on the main actor (the closures call
/// Swift Export-bridged suspend functions, which hop dispatchers internally).
@MainActor
final class SyncSessionController {
    private let connectRealtime: @MainActor () async -> Void
    private let resumeDownloads: @MainActor () async -> Void

    init(
        connectRealtime: @escaping @MainActor () async -> Void,
        resumeDownloads: @escaping @MainActor () async -> Void
    ) {
        self.connectRealtime = connectRealtime
        self.resumeDownloads = resumeDownloads
    }

    /// The awaitable work: open the realtime sync connection (initial pull + SSE firehose), then
    /// resume any interrupted downloads. Separated from `activate()` so tests can await it.
    func connectAndResume() async {
        await connectRealtime()
        await resumeDownloads()
    }

    /// Fire-and-forget entry point for the SwiftUI lifecycle (auth becomes authenticated; scene
    /// becomes active). Idempotent — safe to call repeatedly.
    func activate() {
        Task { await connectAndResume() }
    }
}

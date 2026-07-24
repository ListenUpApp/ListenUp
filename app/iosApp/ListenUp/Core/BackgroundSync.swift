import BackgroundTasks
import Foundation

/// Native iOS background app-refresh sync.
///
/// SwiftUI's `.backgroundTask(.appRefresh:)` modifier (in `ListenUpApp`) registers the handler and
/// supplies the async body; this type owns the two pieces around it — submitting the next request
/// and the run-then-reschedule sequencing — so both are unit-testable without touching the
/// `BGTaskScheduler` singleton.
///
/// **Why native, not the shared `BackgroundSyncScheduler`.** Android schedules a WorkManager
/// periodic worker through that interface; iOS's counterpart was a dead Kotlin/Native wrapper that
/// nothing ever registered a handler for or called `schedule()` on. Per the iOS charter (native
/// surface, shared core) the scheduling is native here and the shared core is
/// `SyncRepository.connectRealtime()` — the same start → catch-up → digest → drain the foreground
/// path runs. `connectRealtime()` is used rather than `refresh()` because it is `Unit`-returning
/// and so Swift-awaitable; `refresh()` returns `AppResult` and would trap across the Swift Export
/// bridge (see `check-no-appresult-await.sh`). At the ≥15-minute cadence of a background wake the
/// debounce inside `lifecycleReconcile` never elides the pass, so the two are equivalent here.
enum BackgroundSync {
    /// Must match `Info.plist` `BGTaskSchedulerPermittedIdentifiers`.
    static let taskIdentifier = "com.calypsan.listenup.sync"

    /// iOS treats this as a floor, not a schedule — it decides the real time from usage, battery,
    /// and network. 15 minutes mirrors the Android WorkManager period.
    static let minimumInterval: TimeInterval = 15 * 60

    /// Submit a request for the next background refresh. Safe to call repeatedly. `submit` is
    /// injectable so a test can assert the request without the real scheduler.
    static func schedule(
        submit: (BGAppRefreshTaskRequest) throws -> Void = { try BGTaskScheduler.shared.submit($0) }
    ) {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: minimumInterval)
        do {
            try submit(request)
        } catch {
            Log.error("Failed to schedule background sync", error: error)
        }
    }

    /// The task body: **reschedule first, then work.** Rescheduling before the sync is the
    /// load-bearing invariant — if the work throws, times out, or the system reclaims the task's
    /// budget mid-run, the next refresh is already queued, so a single failure can never break the
    /// chain and silently stop all future background sync. Both effects are injected so the ordering
    /// is unit-testable.
    static func run(
        reschedule: () -> Void = { schedule() },
        sync: () async -> Void
    ) async {
        reschedule()
        await sync()
    }
}

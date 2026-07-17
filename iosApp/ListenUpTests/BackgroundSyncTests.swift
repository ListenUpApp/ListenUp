import BackgroundTasks
import Testing
@testable import ListenUp

/// Pins the two orderable invariants of native background sync.
///
/// `BackgroundSync.run` calls `reschedule()` then `await sync()` sequentially on the caller's task,
/// so a plain array records the order without any actor coordination.
@Suite("BackgroundSync")
struct BackgroundSyncTests {
    @Test func reschedulesBeforeRunningTheSync() async {
        var steps: [String] = []

        await BackgroundSync.run(
            reschedule: { steps.append("reschedule") },
            sync: { steps.append("sync") }
        )

        // Reschedule-before-work is load-bearing: if the sync throws or the system reclaims the
        // task mid-run, the next refresh must already be queued, or the chain stops forever.
        #expect(steps == ["reschedule", "sync"])
    }

    @Test func reschedulesEvenWhenTheSyncFails() async {
        var steps: [String] = []

        await BackgroundSync.run(
            reschedule: { steps.append("reschedule") },
            // A sync that "fails" (returns without useful work) must not stop the next refresh
            // from being queued.
            sync: { steps.append("sync-failed") }
        )

        #expect(steps.first == "reschedule")
    }

    @Test func scheduleSubmitsAnAppRefreshRequestForTheCorrectIdentifier() {
        var submitted: BGAppRefreshTaskRequest?
        BackgroundSync.schedule(submit: { submitted = $0 })

        #expect(submitted?.identifier == BackgroundSync.taskIdentifier)
        // The earliest-begin floor is set (iOS decides the actual time); prove it is in the future
        // by roughly the configured interval, not left nil.
        let earliest = submitted?.earliestBeginDate?.timeIntervalSinceNow ?? -1
        #expect(earliest > BackgroundSync.minimumInterval - 60)
    }
}

import Foundation
import UIKit
@preconcurrency import Shared

/// The two calls into the shared download service that UIKit's background-session hooks make — a protocol so a test
/// can stand in for the Kotlin graph and for a wake the system only gives a real, backgrounded app.
protocol BackgroundDownloadEvents {
    /// Create the background sessions and reconcile them with the database.
    func attach()
    /// Return once the session `identifier` has delivered this wake's events and the database records them.
    func drain(identifier: String) async throws
}

/// The shared service, through `KoinHelper`.
struct SharedBackgroundDownloads: BackgroundDownloadEvents {
    func attach() {
        KoinHelper.shared.attachBackgroundDownloads()
    }

    func drain(identifier: String) async throws {
        try await KoinHelper.shared.drainBackgroundDownloadEvents(identifier: identifier)
    }
}

/// Keeps the system's background-session completion handlers on the Swift side and calls each exactly once, on the
/// main actor, after the shared layer has filed the wake's events.
///
/// iOS relaunches or wakes the app when a background download finishes and hands it a completion handler; calling it
/// lets the system suspend the app again and refresh its snapshot. Called too early, iOS can suspend the app between a
/// file landing on disk and its row saying so; never called, iOS stops waking it. So the handler waits for the drain —
/// and is called when the drain throws, too. A second wake for a session whose drain is still running joins it.
final class BackgroundDownloadHandoff {
    static let shared = BackgroundDownloadHandoff()

    private let events: any BackgroundDownloadEvents
    private let launchedAt: Date
    private var pending: [String: [() -> Void]] = [:]

    init(events: any BackgroundDownloadEvents = SharedBackgroundDownloads(), launchedAt: Date = Date()) {
        self.events = events
        self.launchedAt = launchedAt
    }

    /// At every launch, signed in or not, with or without a scene: creating the sessions is what hands this process
    /// the transfers the system kept running while it was gone.
    func attach() {
        events.attach()
    }

    /// Hold `completionHandler` until the session `identifier`'s events are filed.
    func handleEvents(identifier: String, completionHandler: @escaping () -> Void) {
        let alreadyDraining = pending[identifier] != nil
        pending[identifier, default: []].append(completionHandler)
        // The uptime tells a relaunch in the background (seconds) from a wake of a suspended app (much longer).
        Log.info("download: background events for \(identifier) uptime=\(Int(Date().timeIntervalSince(launchedAt)))s")
        guard !alreadyDraining else { return }
        Task {
            let started = Date()
            do {
                try await events.drain(identifier: identifier)
            } catch {
                Log.error("download: background drain failed for \(identifier)", error: error)
            }
            Log.info("download: background drain finished for \(identifier) in \(Int(Date().timeIntervalSince(started)))s")
            finish(identifier)
        }
    }

    private func finish(_ identifier: String) {
        let handlers = pending.removeValue(forKey: identifier) ?? []
        handlers.forEach { $0() }
    }
}

#if DEBUG
/// The debug-only way to prove a relaunch in the background: with the launch argument
/// `-LUDownloadsExitOnBackground YES`, the app exits two seconds after it is backgrounded, the way iOS terminates a
/// suspended app, so the next finished transfer must relaunch it. A force-quit from the app switcher would cancel the
/// transfers instead. Compiled out of Release: no shipped app may quit itself.
enum DebugExitOnBackground {
    static func installIfRequested(defaults: UserDefaults = .standard) {
        guard defaults.bool(forKey: "LUDownloadsExitOnBackground") else { return }
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { _ in
            Log.info("download: exiting on background (LUDownloadsExitOnBackground)")
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { exit(0) }
        }
    }
}
#endif

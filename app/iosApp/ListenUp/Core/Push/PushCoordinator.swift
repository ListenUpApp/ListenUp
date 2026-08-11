import UIKit
import UserNotifications
@preconcurrency import Shared

/// Owns the iOS push lifecycle: notification authorization, APNs registration, foreground
/// suppression, and tap-through.
///
/// Activation is deliberately post-auth (`RootView` calls `activate()` when auth turns
/// `.authenticated`, mirroring Android's once-per-session `AppShell` prompt): a user who has
/// never signed in has no reason to grant notifications. The APNs token itself arrives via
/// `AppDelegate.didRegisterForRemoteNotificationsWithDeviceToken`, which hands it to the shared
/// `PushRegistrar`; this type never sees the token.
///
/// Rendering needs no code here: the relay sends `alert` pushes whose `aps` envelope names the
/// bundle's generic loc-keys (`push.generic_title` / `push.generic_body`), so iOS renders them
/// natively with the app closed. Per-type enriched text and action buttons arrive with the
/// notification service extension (spec 2026-08-11 §1.2), alongside the first payload that
/// needs them.
@MainActor
final class PushCoordinator: NSObject {
    static let shared = PushCoordinator()

    private var activated = false

    /// Requests notification authorization (first call shows the system prompt; later calls are
    /// no-ops reflecting the recorded choice) and registers with APNs when permitted. Safe to
    /// call on every auth transition — real work runs once per process.
    func activate() {
        guard !activated else { return }
        activated = true
        UNUserNotificationCenter.current().delegate = self
        Task {
            let center = UNUserNotificationCenter.current()
            let granted = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
            guard granted else {
                Log.info("Notification authorization denied — staying SSE-only")
                return
            }
            UIApplication.shared.registerForRemoteNotifications()
            // Idempotent server-side sync: covers the token that arrived on an earlier run
            // (this call) and the fresh one (the AppDelegate callback's own registration).
            do {
                try await KoinHelper.shared.syncPushRegistration()
            } catch {
                Log.error("Push registration sync failed", error: error)
            }
        }
    }
}

extension PushCoordinator: UNUserNotificationCenterDelegate {
    /// Foreground suppression (Android parity): while the app is visible, the SSE-fed in-app
    /// surface already shows the event, so the system banner stays hidden.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        []
    }

    /// Tap-through. Today every payload type opens the app (the SSE-fed surfaces catch up on
    /// launch); typed deep-link routing lands with the payloads that need it (spec §3).
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        Log.info("Notification tapped — opening app")
    }
}

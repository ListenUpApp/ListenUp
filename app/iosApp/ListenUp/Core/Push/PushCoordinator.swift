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

    /// Where shade taps land, set by `RootView` (which owns the router as `@State` so cold-launch
    /// taps outlive the tab shell). A property on this singleton — not a `PushTapRouter.shared`
    /// global — because the coordinator already owns every delegate callback and `RootView`
    /// already talks to `PushCoordinator.shared`; weak so the coordinator never keeps UI state
    /// alive. `didReceive` cannot fire before it is set: the delegate is only assigned in
    /// `activate()`, which `RootView` calls after wiring the router.
    weak var tapRouter: PushTapRouter?

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
    /// Foreground suppression (Android parity): while the app is visible the running UI is the
    /// better surface, so the system banner stays hidden — EXCEPT for a test notification, whose
    /// whole purpose is to prove a banner can reach this device, and which is triggered from
    /// Settings and therefore always arrives foregrounded. See `PushForegroundPolicy`.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        let userInfo = notification.request.content.userInfo
        return PushForegroundPolicy.presentsInForeground(userInfo: userInfo) ? [.banner, .sound] : []
    }

    /// Tap-through: the default body tap hands the payload to `PushTapRouter`, which decodes it
    /// through the Kotlin `PushTapRouting` seam and holds the outcome until the tab shell
    /// consumes it. Dismiss and action taps stay untouched (body-tap-only scope, spec §3).
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier else { return }
        let payload = response.notification.request.content.userInfo["payload"] as? String
        await MainActor.run { tapRouter?.handleTap(payloadJson: payload) }
    }
}

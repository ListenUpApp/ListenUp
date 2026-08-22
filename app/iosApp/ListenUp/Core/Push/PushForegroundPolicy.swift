import Foundation

/// Decides whether an arriving push should show a system banner while the app is in the foreground.
///
/// Pure and unit-tested rather than inline in `PushCoordinator`, because the rule has a genuine
/// exception and getting it wrong is invisible: a suppressed banner looks exactly like a push that
/// never arrived. Mirrors `PlayerGestureMath` — and the Android side's `PushForegroundPolicy`, so
/// the two platforms answer this the same way.
enum PushForegroundPolicy {
    /// Whether the payload in `userInfo` should present while the app is foregrounded.
    ///
    /// A visible app normally suppresses the banner — the running UI is the better surface. A test
    /// notification is the exception, and has to be: it exists purely to prove a notification can
    /// reach this device, and it is triggered from Settings, so the app is *necessarily* foregrounded
    /// when it lands. Suppressing it guaranteed the one thing it was built to demonstrate could
    /// never be demonstrated.
    static func presentsInForeground(userInfo: [AnyHashable: Any]) -> Bool {
        isTestNotification(userInfo: userInfo)
    }

    /// `true` when the relay's opaque payload is a test notification.
    ///
    /// Reads the payload the same way the notification-service extension does — the relay forwards
    /// it as a JSON *string* under `payload`. Deliberately duplicated rather than shared with
    /// `PushPayloadContent`: that type lives in the extension, which does not link the Shared
    /// framework so it can start within its few-second budget.
    static func isTestNotification(userInfo: [AnyHashable: Any]) -> Bool {
        guard let raw = userInfo["payload"] as? String,
              let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = object["type"] as? String
        else { return false }
        return type == "test"
    }
}

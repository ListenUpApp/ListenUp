import Foundation

/// Localized title/body for a decoded push payload.
///
/// Strings are resolved from the **containing app's** bundle, not this extension's. An `.appex`
/// lives at `MyApp.app/PlugIns/…`, so the app bundle is two levels up — and reading from there
/// means the one string catalog the app already ships is the single source of this copy. The
/// alternative, duplicating the catalog into the extension target, is a second copy that drifts
/// silently the first time someone edits only one of them.
enum NotificationCopy {
    struct Text {
        let title: String
        let body: String
    }

    /// The containing app's bundle, or nil if the layout is ever not what it is today — in which
    /// case the caller keeps the relay's generic copy rather than showing a raw key.
    private static let appBundle: Bundle? = {
        let appURL = Bundle.main.bundleURL
            .deletingLastPathComponent() // …/PlugIns
            .deletingLastPathComponent() // …/MyApp.app
        return Bundle(url: appURL)
    }()

    /// Looks `key` up in the app bundle. Returns nil when the key is missing, so a copy change
    /// that lands in the app before this extension knows about it degrades to generic text rather
    /// than displaying the key itself to a user.
    private static func localized(_ key: String) -> String? {
        guard let appBundle else { return nil }
        let fallback = "\u{0}MISSING\u{0}"
        let value = appBundle.localizedString(forKey: key, value: fallback, table: nil)
        return value == fallback ? nil : value
    }

    static func forPayload(_ payload: PushPayloadContent) -> Text? {
        switch payload {
        case .registrationApproval:
            // The name is NOT resolved here, unlike Android. Naming the waiting person needs the
            // synced admin roster, which lives in the app's database — reachable only by linking
            // the shared framework into an extension with a 24 MB ceiling. "Someone" is the honest
            // trade; tapping through shows exactly who.
            return text("push.registration_request_title", "push.registration_request_body_unknown")
        case .registrationDecision(let approved):
            return approved
                ? text("push.registration_approved_title", "push.registration_approved_body")
                : text("push.registration_denied_title", "push.registration_denied_body")
        case .test:
            return text("push.test_title", "push.test_body")
        }
    }

    private static func text(_ titleKey: String, _ bodyKey: String) -> Text? {
        guard let title = localized(titleKey), let body = localized(bodyKey) else { return nil }
        return Text(title: title, body: body)
    }
}

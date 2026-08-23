import Foundation

/// The push payloads this extension knows how to render, decoded from the relay's opaque
/// `payload` key.
///
/// Deliberately a small hand-written mirror of the shared Kotlin `PushPayload` rather than the
/// real thing: a notification service extension runs under a hard 24 MB memory ceiling and gets
/// a few seconds to finish, so linking the whole Shared framework to decode two string fields
/// would be a poor trade — and a framework that fails to load here means the notification falls
/// back to generic text, which is the exact failure this extension exists to remove.
///
/// The discriminators are the wire `@SerialName`s. No test compiles this file directly (it
/// belongs to the extension target only); the pins are `PushForegroundPolicyTests` — whose
/// `PushForegroundPolicy` reads the identical wire shape for the `test` discriminator — and,
/// transitively, the Kotlin contract tests on `PushPayload` plus `NotificationCopy`'s rendering
/// of what is decoded here.
enum PushPayloadContent {
    case registrationApproval(userId: String)
    case registrationDecision(approved: Bool)
    case test

    /// Decodes the relay's `payload` entry. Returns nil for anything unrecognised — including a
    /// discriminator from a server newer than this app — so an unknown push renders the generic
    /// copy rather than nothing at all.
    static func from(userInfo: [AnyHashable: Any]) -> PushPayloadContent? {
        // The relay forwards the payload as a JSON *string* under `payload` (see the relay's
        // apns.ts), not as a nested object.
        guard let raw = userInfo["payload"] as? String,
              let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = object["type"] as? String
        else { return nil }

        switch type {
        case "registration_approval":
            guard let userId = object["userId"] as? String else { return nil }
            return .registrationApproval(userId: userId)
        case "registration_decision":
            guard let approved = object["approved"] as? Bool else { return nil }
            return .registrationDecision(approved: approved)
        case "test":
            return .test
        default:
            return nil
        }
    }
}

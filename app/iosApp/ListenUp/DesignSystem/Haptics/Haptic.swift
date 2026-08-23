import SwiftUI

/// The app's semantic haptic vocabulary, mirroring the Compose `Haptics` verbs. Each case maps to
/// a SwiftUI `SensoryFeedback`; views fire them via `.haptic(_:trigger:)`, which honors the user's
/// haptics setting.
enum Haptic {
    case selectionTick
    case press
    case toggleOn
    case toggleOff
    case longPress
    case thresholdActivate
    case commit

    /// The SwiftUI feedback for this verb.
    var feedback: SensoryFeedback {
        switch self {
        case .selectionTick: .selection
        // A transport press: the restrained end of Apple's palette, not a notification buzz.
        case .press: .impact(weight: .light)
        case .toggleOn, .toggleOff, .longPress: .impact(weight: .light)
        case .thresholdActivate: .impact(flexibility: .rigid)
        // Apple's notification family means "a task completed" — that is exactly this verb.
        // Android maps the same verb to Confirm; the platforms are meant to differ here.
        case .commit: .success
        }
    }

    /// The feedback to play, or `nil` when haptics are disabled (the gate).
    func feedback(enabled: Bool) -> SensoryFeedback? {
        enabled ? feedback : nil
    }
}

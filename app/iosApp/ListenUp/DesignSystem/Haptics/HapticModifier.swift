import SwiftUI

extension View {
    /// Plays [haptic] whenever [trigger] changes, honoring the user's haptics setting. The
    /// idiomatic SwiftUI gate: the `.sensoryFeedback` closure returns `nil` to suppress.
    func haptic<T: Equatable>(_ haptic: Haptic, trigger: T) -> some View {
        modifier(HapticModifier(haptic: haptic, trigger: trigger))
    }
}

private struct HapticModifier<T: Equatable>: ViewModifier {
    @Environment(HapticsSettings.self) private var settings
    let haptic: Haptic
    let trigger: T

    func body(content: Content) -> some View {
        content.sensoryFeedback(trigger: trigger) { _, _ in
            haptic.feedback(enabled: settings.isEnabled)
        }
    }
}

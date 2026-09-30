import SwiftUI

extension View {
    /// Labels a control the system fills with the accent — `.borderedProminent`, `.glassProminent` —
    /// in the on-coral ink (`Color.luOnTint`): white on the light coral, deep ink on the dark coral,
    /// where white would sit at 2.85:1 (HIG, Accessibility: 4.5:1 for text up to 17pt).
    ///
    /// Only while enabled: a disabled prominent control drops its coral for a grey fill, and the
    /// system's own dimmed label is the legible one there (an explicit ink rendered near-invisible on
    /// the dark disabled fill).
    func onBrandFillLabel() -> some View {
        modifier(OnBrandFillLabel())
    }
}

private struct OnBrandFillLabel: ViewModifier {
    @Environment(\.isEnabled) private var isEnabled

    func body(content: Content) -> some View {
        if isEnabled {
            content.foregroundStyle(Color.luOnTint)
        } else {
            content
        }
    }
}

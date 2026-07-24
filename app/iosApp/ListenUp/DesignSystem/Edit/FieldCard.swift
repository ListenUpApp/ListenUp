import SwiftUI

/// The clean-coral inset-card chrome — a rounded `luSurface2` surface with a hairline
/// border — for a single standalone card. Matches the surface `FieldGroup` gives a row
/// group, so a lone form field reads as part of the same design language.
private struct FieldCardModifier: ViewModifier {
    @Environment(\.displayScale) private var displayScale
    private var hairline: CGFloat { 1 / max(displayScale, 1) }

    func body(content: Content) -> some View {
        content
            .background(Color.luSurface2)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Color.luSeparator, lineWidth: hairline)
            )
            .shadow(color: .black.opacity(0.05), radius: 3, x: 0, y: 1)
    }
}

extension View {
    /// Wraps the view in a single rounded inset card (the `FieldGroup` surface, for one card).
    func fieldCard() -> some View { modifier(FieldCardModifier()) }
}

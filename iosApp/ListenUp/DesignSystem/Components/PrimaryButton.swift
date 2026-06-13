import SwiftUI

/// The one prominent coral call-to-action, app-wide: full-width flat fill, optional
/// leading icon, loading spinner. Generalizes the former `AuthPrimaryButton`.
struct PrimaryButton: View {
    let title: String
    var icon: String?
    var tint: Color = .luTint
    var isLoading: Bool = false
    /// The soft coral glow under the fill. On by default; auth passes `false` to keep its
    /// deliberately flat look over the aurora backdrop.
    var hasShadow: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if isLoading {
                    ProgressView().tint(Color.luOnTint)
                } else {
                    HStack(spacing: 9) {
                        if let icon { Image(systemName: icon) }
                        Text(title).font(.headline)
                    }
                }
            }
            .foregroundStyle(Color.luOnTint)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(tint))
            .shadow(
                color: hasShadow ? tint.opacity(0.32) : .clear,
                radius: hasShadow ? 12 : 0,
                x: 0,
                y: hasShadow ? 6 : 0
            )
        }
        .buttonStyle(PrimaryPressStyle())
        .disabled(isLoading)
        .accessibilityLabel(isLoading ? String(localized: "common.accessibility_loading") : title)
        .accessibilityAddTraits(isLoading ? [.updatesFrequently] : [])
        .accessibilityRemoveTraits(isLoading ? [.isButton] : [])
    }
}

/// Subtle press feedback: scale + deepen toward the pressed tint.
private struct PrimaryPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color.listenUpTintPressed.opacity(configuration.isPressed ? 0.5 : 0))
            )
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

#Preview("PrimaryButton") {
    VStack(spacing: 16) {
        PrimaryButton(title: "Continue Book 3", icon: "play.fill", action: {})
        PrimaryButton(title: "Sign In", action: {})
        PrimaryButton(title: "Saving…", isLoading: true, action: {})
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}

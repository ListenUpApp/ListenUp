import SwiftUI

/// The one prominent action per auth screen: a flat coral fill, no gloss, no trailing
/// arrow (modern Cupertino `.borderedProminent` is a flat solid fill). Full-width.
struct AuthPrimaryButton: View {
    var title: String
    var isLoading: Bool = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if isLoading {
                    ProgressView().tint(.white)
                } else {
                    Text(title).font(.headline)
                }
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: AuthMetrics.primaryButtonHeight)
            .background(
                RoundedRectangle(cornerRadius: AuthMetrics.primaryButtonCornerRadius, style: .continuous)
                    .fill(Color.listenUpOrange)
            )
        }
        .buttonStyle(AuthPressStyle())
        .disabled(isLoading)
        .accessibilityLabel(isLoading ? String(localized: "common.accessibility_loading") : title)
        .accessibilityHint(isLoading ? "" : "Double tap to \(title.lowercased())")
        .accessibilityAddTraits(isLoading ? [.updatesFrequently] : [])
        .accessibilityRemoveTraits(isLoading ? [.isButton] : [])
    }
}

/// Subtle press feedback (scale + deepen toward the pressed tint).
private struct AuthPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay(
                RoundedRectangle(cornerRadius: AuthMetrics.primaryButtonCornerRadius, style: .continuous)
                    .fill(Color.listenUpTintPressed.opacity(configuration.isPressed ? 0.5 : 0))
            )
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

import SwiftUI

/// Renders whatever `AppMessageCenter` currently holds, as a card above the mini player.
///
/// Bottom, not top: the top safe area already belongs to the *persistent* banners
/// (`SessionLapsedBanner`, `ConnectionHealthBanner`, `ServerUnreachableBanner`), and a standing
/// condition and a passing remark should not look the same. The inset uses the mini player's own
/// published footprint rather than a hand-tuned number, so a change to the bar moves this too.
private struct AppMessageHost: ViewModifier {
    let center: AppMessageCenter

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if let message = center.current {
                    card(message)
                        .padding(.horizontal, 16)
                        .padding(.bottom, MiniPlayerBar.barHeight + MiniPlayerBar.tabBarClearance + 12)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        // Keyed on the id, so a queued message replacing a dismissed one animates as
                        // a new card rather than as text changing inside the old one.
                        .id(message.id)
                }
            }
            .animation(.smooth(duration: 0.28), value: center.current?.id)
    }

    private func card(_ message: AppMessage) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            if message.kind == .error {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.subheadline)
                    .foregroundStyle(.red)
            }
            Text(message.text)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.18), radius: 12, y: 4)
        // A transient message must reach VoiceOver, which does not narrate an overlay appearing.
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isStaticText)
        .accessibilityLabel(message.text)
        .gesture(
            DragGesture(minimumDistance: 20)
                .onEnded { value in
                    if value.translation.height > 0 { center.dismissCurrent() }
                }
        )
        .onTapGesture { center.dismissCurrent() }
    }
}

extension View {
    /// Hosts the app-wide transient message surface. Apply once, on the authenticated shell.
    func appMessageHost(_ center: AppMessageCenter) -> some View {
        modifier(AppMessageHost(center: center))
    }
}

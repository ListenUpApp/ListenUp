import SwiftUI

/// "LIVE" on a listener's art: an uppercase badge with a coral dot (HIG badge treatment; spec §3.5).
/// Its text is what VoiceOver reads, so it carries no separate label.
struct LiveBadge: View {
    var body: some View {
        HStack(spacing: Spacing.xxs) {
            Circle()
                .fill(Color.luTint)
                .frame(width: 7, height: 7)
                .accessibilityHidden(true)
            Text(String(localized: "discover.live"))
                .font(.caption2.weight(.bold))
                .textCase(.uppercase)
        }
        .padding(.horizontal, Spacing.xs)
        .padding(.vertical, Spacing.xxs)
        .background(Color(.systemBackground).opacity(0.92), in: Capsule())
        .foregroundStyle(Color.luTint)
    }
}

#Preview {
    LiveBadge().padding().background(Color.gray)
}

import SwiftUI

/// A compact streak badge: a coral flame and the current run of consecutive listening days.
///
/// Shown only when the user has an active streak (`hasStreak`). The icon is decorative; the row
/// owns a combined `.accessibilityLabel` so VoiceOver reads "N day streak" as one element.
struct StreakBadge: View {
    let currentStreak: Int

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "flame.fill")
                .font(.headline)
                .foregroundStyle(Color.listenUpOrange)

            Text(String(format: String(localized: "home.day_streak"), currentStreak))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.listenUpOrange.opacity(0.12), in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(format: String(localized: "home.day_streak"), currentStreak))
    }
}

// MARK: - Preview

#Preview("Streak Badge") {
    VStack(spacing: 16) {
        StreakBadge(currentStreak: 1)
        StreakBadge(currentStreak: 7)
        StreakBadge(currentStreak: 42)
    }
    .padding()
}

import SwiftUI

/// A compact streak badge: a coral flame and the user's listening streak.
///
/// Two cases, mirroring Android's `StreakIndicator`:
/// - An active run (`currentStreak > 0`) reads "N day streak".
/// - No active run but a past best (`currentStreak == 0 && longestStreak > 0`) reads
///   "Best: N day streak" — so a lapsed streak still celebrates the record instead of
///   showing a misleading "0 day streak".
///
/// The icon is decorative; the row owns a combined `.accessibilityLabel` so VoiceOver reads
/// the streak as one element.
struct StreakBadge: View {
    let currentStreak: Int
    let longestStreak: Int

    private var label: String {
        if currentStreak > 0 {
            return String(format: String(localized: "home.day_streak"), currentStreak)
        }
        return String(format: String(localized: "home.best_day_streak"), longestStreak)
    }

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "flame.fill")
                .font(.headline)
                .foregroundStyle(Color.listenUpOrange)

            Text(label)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.listenUpOrange.opacity(0.12), in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }
}

// MARK: - Preview

#Preview("Streak Badge") {
    VStack(spacing: 16) {
        StreakBadge(currentStreak: 1, longestStreak: 1)
        StreakBadge(currentStreak: 7, longestStreak: 14)
        StreakBadge(currentStreak: 42, longestStreak: 42)
        StreakBadge(currentStreak: 0, longestStreak: 9)
    }
    .padding()
}

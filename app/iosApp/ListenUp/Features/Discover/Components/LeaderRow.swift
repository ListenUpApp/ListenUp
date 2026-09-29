import SwiftUI

/// A flat ranked leaderboard row: a medal-colored rank (gold/silver/bronze for 1/2/3),
/// a neutral initials avatar, the name, and the value. The current user's row is
/// coral-tinted with a "(You)" suffix, per the design.
struct LeaderRow: View {
    let row: LeaderboardRow

    var body: some View {
        NavigationLink(value: ProfileDestination(userId: row.id)) {
            HStack(spacing: 13) {
                Text("\(row.rank)")
                    .font(.callout.weight(.bold).monospacedDigit())
                    .foregroundStyle(rankColor)
                    .frame(width: 22)

                UserAvatarView(userId: row.id, fallbackName: row.displayName, size: 38)

                Text(name)
                    .font(.callout.weight(row.isCurrentUser ? .semibold : .regular))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                Spacer(minLength: 8)

                Text(row.value)
                    .font(.system(.subheadline, design: .default).monospacedDigit())
                    .fontWeight(row.isCurrentUser ? .bold : .medium)
                    .foregroundStyle(row.isCurrentUser ? Color.luTint : Color.secondary)
            }
            .padding(.vertical, Spacing.s)
            .padding(.horizontal, row.isCurrentUser ? Spacing.s : 0)
            .background(rowBackground)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(row.rank). \(name), \(row.value)")
        }
        .buttonStyle(.pressScaleRow)
    }

    private var name: String {
        row.isCurrentUser ? "\(row.displayName) (You)" : row.displayName
    }

    @ViewBuilder private var rowBackground: some View {
        if row.isCurrentUser {
            RoundedRectangle(cornerRadius: Radius.m)
                .fill(Color.luTint.opacity(0.07))
        }
    }

    /// Medal colors for the podium; neutral tertiary for the rest.
    private var rankColor: Color {
        switch row.rank {
        case 1: Color.luPodiumGold
        case 2: Color.luPodiumSilver
        case 3: Color.luPodiumBronze
        default: Color.luLabel3
        }
    }
}

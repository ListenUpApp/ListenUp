import SwiftUI

/// A ranked leaderboard row: a medal-coloured rank (gold/silver/bronze for 1/2/3), the person's
/// avatar, their name — "You" on your own row, on a tinted fill — and the value. One link to the
/// person's profile; VoiceOver reads the rank first ("Rank 1, Maya Chen, 14h 20m").
struct LeaderRow: View {
    let row: LeaderboardRow

    var body: some View {
        NavigationLink(value: ProfileDestination(userId: row.id)) {
            HStack(spacing: Spacing.s) {
                Text("\(row.rank)")
                    .font(.callout.weight(.bold).monospacedDigit())
                    .foregroundStyle(rankColor)
                    .frame(minWidth: 22)

                UserAvatarView(userId: row.id, fallbackName: row.displayName, size: 38)

                Text(name)
                    .font(.callout.weight(row.isCurrentUser ? .semibold : .regular))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                Spacer(minLength: Spacing.xs)

                Text(row.value)
                    .font(.subheadline.monospacedDigit())
                    .fontWeight(row.isCurrentUser ? .bold : .medium)
                    // CoralInk, not the brand coral: coral text on its own 12% wash is under 4.5:1.
                    .foregroundStyle(row.isCurrentUser ? Color.luTintInk : Color.secondary)
            }
            .padding(.vertical, Spacing.xs)
            .padding(.horizontal, row.isCurrentUser ? Spacing.xs : 0)
            .frame(minHeight: 44)
            .background(rowBackground)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressScaleRow)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(.isLink)
    }

    /// "Rank 1, Maya Chen, 14h 20m": the rank first, as the board reads it (spec §8.2).
    var accessibilityText: String {
        let rank = String(format: String(localized: "discover.leaderboard_rank_a11y"), Int32(row.rank))
        return "\(rank), \(name), \(row.value)"
    }

    private var name: String {
        row.isCurrentUser ? String(localized: "discover.leaderboard_you") : row.displayName
    }

    @ViewBuilder private var rowBackground: some View {
        if row.isCurrentUser {
            RoundedRectangle(cornerRadius: Radius.m)
                .fill(Color.luTint.opacity(0.12))
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

import SwiftUI

/// A ranked leaderboard row: the rank in a 26pt disc (a gold, silver or bronze medal for 1/2/3), the
/// person's avatar, their name — "You" on your own row, on a tinted fill — and the value. One link to
/// the person's profile; VoiceOver reads the rank first ("Rank 1, Maya Chen, 14h 20m").
struct LeaderRow: View {
    let row: LeaderboardRow

    /// The rank disc's diameter: the board's 26pt, growing with the numeral's text size.
    @ScaledMetric(relativeTo: .footnote) private var rankDiameter: CGFloat = 26

    var body: some View {
        NavigationLink(value: ProfileDestination(userId: row.id)) {
            HStack(spacing: Spacing.s) {
                rankDisc

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

    /// The numeral on its medal, dark ink on the podium's fills; below the podium, a secondary numeral
    /// on no fill (tertiary would fall under the contrast floor).
    @ViewBuilder private var rankDisc: some View {
        let numeral = Text("\(row.rank)").font(.footnote.weight(.bold).monospacedDigit())
        if let medal = LeaderRankMedal(rank: row.rank) {
            numeral
                .foregroundStyle(Color.luPodiumInk)
                .frame(width: rankDiameter, height: rankDiameter)
                .background(medal.fill, in: Circle())
        } else {
            numeral
                .foregroundStyle(.secondary)
                .frame(width: rankDiameter, height: rankDiameter)
        }
    }
}

/// The podium medal a rank wears: gold, silver and bronze for 1–3, none below.
enum LeaderRankMedal: Equatable {
    case gold, silver, bronze

    init?(rank: Int) {
        switch rank {
        case 1: self = .gold
        case 2: self = .silver
        case 3: self = .bronze
        default: return nil
        }
    }

    var fill: Color {
        switch self {
        case .gold: .luPodiumGold
        case .silver: .luPodiumSilver
        case .bronze: .luPodiumBronze
        }
    }
}

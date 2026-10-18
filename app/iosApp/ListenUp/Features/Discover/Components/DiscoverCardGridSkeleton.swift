import SwiftUI

/// A Discover section's cards while they load, laid out by `DiscoverCardGrid` itself: a rail on iPhone
/// and the section's grid on iPad, so the skeleton has the shape the cards will arrive in (spec §3.8).
/// Each card is a square cover and its caption lines. Hidden from VoiceOver; the heading says what is coming.
struct DiscoverCardGridSkeleton: View {
    let isRegular: Bool
    let cardWidth: CGFloat
    let regularColumnCount: Int
    let horizontalInset: CGFloat
    var captionLines = 2
    var spacing: CGFloat = Spacing.m

    /// One placeholder card.
    struct Slot: Identifiable {
        let id: Int
    }

    /// Pure: a full row of the grid on iPad, four cards on the iPhone rail.
    static func slotCount(isRegular: Bool, regularColumnCount: Int) -> Int {
        isRegular ? max(regularColumnCount, 1) : 4
    }

    var body: some View {
        DiscoverCardGrid(
            items: (0..<Self.slotCount(isRegular: isRegular, regularColumnCount: regularColumnCount)).map(Slot.init),
            isRegular: isRegular,
            cardWidth: cardWidth,
            regularColumnCount: regularColumnCount,
            horizontalInset: horizontalInset,
            spacing: spacing
        ) { _, width in
            card(width: width)
        }
        .scrollDisabled(true)
        .accessibilityHidden(true)
    }

    private func card(width: CGFloat?) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            RoundedRectangle(cornerRadius: Radius.s)
                .fill(Color.gray.opacity(0.2))
                .aspectRatio(1, contentMode: .fit)
                .shimmer()
            ForEach(0..<captionLines, id: \.self) { line in
                RoundedRectangle(cornerRadius: Radius.xs)
                    .fill(Color.gray.opacity(0.2))
                    .frame(height: 12)
                    .padding(.trailing, line == 0 ? 0 : 40)
                    .shimmer()
            }
        }
        .frame(width: width)
        .frame(maxWidth: width == nil ? .infinity : nil, alignment: .leading)
    }
}

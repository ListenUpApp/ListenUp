import SwiftUI

/// How Discover lays out a section's cards: a horizontal rail at compact width, and at regular width
/// a grid of the board's column count that shows **every** item, wrapping as it needs to (iOS rule 12).
///
/// Showing everything rather than one row of as many as fit is the plan's Q2 default: nothing is
/// hidden and no width needs measuring. A different answer to Q2 is a change to this one view. The
/// count is fixed per section (spec §3.7: five for people and picks, six for recently added), so the
/// cards share the width rather than leaving a card's worth of it empty.
struct DiscoverCardGrid<Item: Identifiable, Card: View>: View {
    let items: [Item]
    let isRegular: Bool
    let cardWidth: CGFloat
    /// How many cards make a row in the regular-width grid.
    let regularColumnCount: Int
    let horizontalInset: CGFloat
    var spacing: CGFloat = Spacing.m
    @ViewBuilder let card: (Item, CGFloat?) -> Card

    /// Pure: `count` equal flexible columns, top-aligned.
    static func regularColumns(count: Int, spacing: CGFloat) -> [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: spacing, alignment: .top), count: max(count, 1))
    }

    var body: some View {
        if isRegular {
            LazyVGrid(
                columns: Self.regularColumns(count: regularColumnCount, spacing: spacing),
                alignment: .leading,
                spacing: spacing
            ) {
                ForEach(items) { item in card(item, nil) }
            }
            .padding(.horizontal, horizontalInset)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: spacing) {
                    ForEach(items) { item in card(item, cardWidth) }
                }
                .padding(.horizontal, horizontalInset)
            }
        }
    }
}

/// A Discover section's heading: the title (a header to VoiceOver) and an optional quieter line under it.
struct DiscoverSectionHeader: View {
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
            if let subtitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

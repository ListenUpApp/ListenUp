import SwiftUI

/// A shelf's 2×2 cover mosaic: up to four member covers in shelf order, then the grouped fill where a
/// shelf has fewer (spec §2.7, §4.3). Decorative — the card around it names the shelf — so it is hidden
/// from VoiceOver.
struct ShelfCoverMosaic: View {
    let covers: [DiscoverShelfCover]

    /// Pure: always four tiles, the covers first, then empty tiles.
    nonisolated static func tiles(for covers: [DiscoverShelfCover]) -> [DiscoverShelfCover?] {
        (0..<4).map { covers.indices.contains($0) ? covers[$0] : nil }
    }

    var body: some View {
        let tiles = Self.tiles(for: covers)
        Grid(horizontalSpacing: Spacing.xxs, verticalSpacing: Spacing.xxs) {
            ForEach(0..<2, id: \.self) { row in
                GridRow {
                    ForEach(0..<2, id: \.self) { column in
                        tile(tiles[row * 2 + column])
                    }
                }
            }
        }
        .padding(Spacing.xxs)
        .aspectRatio(1, contentMode: .fit)
        .background(
            Color(.secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: Radius.m, style: .continuous)
        )
        .accessibilityHidden(true)
    }

    @ViewBuilder private func tile(_ cover: DiscoverShelfCover?) -> some View {
        if let cover {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    BookCoverImage(bookId: cover.bookId, coverPath: cover.coverPath, coverHash: cover.coverHash)
                        .scaledToFill()
                }
                .clipShape(RoundedRectangle(cornerRadius: Radius.xs, style: .continuous))
        } else {
            RoundedRectangle(cornerRadius: Radius.xs, style: .continuous)
                .fill(Color(.tertiarySystemFill))
                .aspectRatio(1, contentMode: .fit)
        }
    }
}

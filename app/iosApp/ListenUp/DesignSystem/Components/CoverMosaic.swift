import SwiftUI

/// A shelf's 2×2 mosaic of member covers (spec §2.7; Discover's shelves reuse it). Missing tiles are quiet fills.
/// Decorative: the card's text names the shelf.
struct CoverMosaic: View {
    let covers: [DiscoverShelfCover]
    var spacing: CGFloat = 4

    var body: some View {
        Grid(horizontalSpacing: spacing, verticalSpacing: spacing) {
            ForEach(0..<2, id: \.self) { row in
                GridRow {
                    ForEach(0..<2, id: \.self) { column in tile(at: row * 2 + column) }
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }

    @ViewBuilder private func tile(at index: Int) -> some View {
        if covers.indices.contains(index) {
            let cover = covers[index]
            BookCoverImage(bookId: cover.bookId, coverPath: cover.coverPath, coverHash: cover.coverHash)
                .aspectRatio(1, contentMode: .fill)
                .clipShape(RoundedRectangle(cornerRadius: Radius.s, style: .continuous))
        } else {
            RoundedRectangle(cornerRadius: Radius.s, style: .continuous)
                .fill(Color.luFill)
                .aspectRatio(1, contentMode: .fit)
        }
    }
}

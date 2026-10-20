import SwiftUI
import Shared

/// A single cover's image source, decoupled from any Kotlin type so `CoverStack` is
/// reusable and previewable in plain Swift.
struct CoverArt: Identifiable, Equatable, Hashable {
    let id: String
    let coverPath: String?
    let coverHash: String?

    init(id: String, coverPath: String?, coverHash: String?) {
        self.id = id
        self.coverPath = coverPath
        self.coverHash = coverHash
    }

    /// Maps a `BookListItem` to its cover.
    init(book: BookListItem) {
        self.id = book.idString
        self.coverPath = book.coverPath
        self.coverHash = book.coverHash
    }

    /// Maps a native `BookRow` to its cover (no Swift Export re-bridge).
    init(book: BookRow) {
        self.id = book.id
        self.coverPath = book.coverPath
        self.coverHash = book.coverHash
    }
}

/// Static overlapping cover deck — the clean-coral hero for series rows, grids, detail
/// heroes, and series mini-cards. Layers up to `maxCovers` covers, each deeper layer
/// indented by `peek` and scaled down 5%, with a `ring` border and one stack shadow.
/// Geometry comes from `CoverStackLayout` (unit-tested).
struct CoverStack: View {
    let covers: [CoverArt]
    var size: CGFloat
    var peek: CGFloat
    var maxCovers: Int = 4
    var ring: Color = .luSurface2
    /// A count drawn at the deck's bottom-trailing corner (spec c6: a series' book count); nil draws none.
    var countBadge: Int?

    init(
        covers: [CoverArt],
        size: CGFloat,
        peek: CGFloat,
        maxCovers: Int = 4,
        ring: Color = .luSurface2,
        countBadge: Int? = nil
    ) {
        self.covers = covers
        self.size = size
        self.peek = peek
        self.maxCovers = maxCovers
        self.ring = ring
        self.countBadge = countBadge
    }

    /// Convenience: build a deck from series/contributor books.
    init(books: [BookListItem], size: CGFloat, peek: CGFloat, maxCovers: Int = 4, ring: Color = .luSurface2) {
        self.init(covers: books.map(CoverArt.init(book:)), size: size, peek: peek, maxCovers: maxCovers, ring: ring)
    }

    private var visible: [CoverArt] { Array(covers.prefix(CoverStackLayout.layers(of: covers.count, max: maxCovers))) }
    private var layout: CoverStackLayout {
        CoverStackLayout(coverCount: visible.count, size: size, peek: peek)
    }
    private var cornerRadius: CGFloat { size * 0.09 }

    var body: some View {
        ZStack(alignment: .leading) {
            if visible.isEmpty {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(Color.luFill)
                    .frame(width: size, height: size)
            } else {
                ForEach(Array(visible.enumerated()).reversed(), id: \.element.id) { index, art in
                    BookCoverImage(bookId: art.id, coverPath: art.coverPath, coverHash: art.coverHash)
                        .frame(width: size, height: size)
                        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                                .stroke(ring, lineWidth: 2)
                        )
                        .scaleEffect(layout.scale(at: index), anchor: .topLeading)
                        .offset(x: layout.xOffset(at: index))
                        .zIndex(layout.zIndex(at: index))
                }
            }
        }
        .frame(width: layout.totalWidth, height: size, alignment: .leading)
        .shadow(color: .black.opacity(0.10), radius: 9, x: 0, y: 5)
        .overlay(alignment: .bottomTrailing) {
            if let countBadge, let text = SeriesCountBadge.text(countBadge) {
                // Ink on paper (board `Library-WebPhone`): the label colour on the background colour, so it holds in
                // dark mode too.
                Text(text)
                    .font(.caption.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(Color(.systemBackground))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(.primary))
                    .offset(x: 4, y: 4)
            }
        }
        // Decorative: the enclosing row / hero (a labeled NavigationLink) owns the
        // accessibility label, so the deck stays hidden to avoid double-spoken covers.
        .accessibilityHidden(true)
    }
}

/// A series deck's count badge: "7" on the covers. A one-book series has none — its meta line already says "1 book".
enum SeriesCountBadge {
    static func text(_ count: Int) -> String? {
        count > 1 ? count.formatted() : nil
    }

    /// What the badge means, for the card's own accessibility value; the badge itself is hidden with the deck.
    static func accessibilityLabel(_ count: Int) -> String {
        String(format: String(localized: "library.series_count_badge_a11y"), count)
    }
}

// MARK: - Preview

#Preview("CoverStack") {
    let mock = (0 ..< 5).map { CoverArt(id: "\($0)", coverPath: nil, coverHash: nil) }
    return VStack(alignment: .leading, spacing: 44) {
        CoverStack(covers: Array(mock.prefix(1)), size: 76, peek: 17)
        CoverStack(covers: Array(mock.prefix(3)), size: 76, peek: 17)
        CoverStack(covers: mock, size: 112, peek: 26, maxCovers: 5)
        CoverStack(covers: [], size: 76, peek: 17)
    }
    .padding(48)
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    .background(Color.luSurface)
}

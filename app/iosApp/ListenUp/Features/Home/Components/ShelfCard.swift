import SwiftUI

/// A single "My Shelves" card: a fanned stack of cover art, the shelf name, and a count/duration
/// subtitle. Glass-backed to match `HomeStatsCard`.
///
/// Pure visual tile — `MyShelvesRow` wraps it in a `NavigationLink(value:)` so the tap pushes the
/// shelf's detail onto the Home stack, matching how book/series covers route elsewhere.
struct ShelfCard: View {
    let shelf: ShelfItem

    private let cardWidth: CGFloat = 200
    private let coverSize: CGFloat = 64

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            coverStack

            VStack(alignment: .leading, spacing: 2) {
                Text(shelf.name)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .foregroundStyle(.primary)

                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(16)
        .frame(width: cardWidth, alignment: .leading)
        .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: 20))
        .overlay {
            RoundedRectangle(cornerRadius: 20)
                .strokeBorder(Color.luSeparator, lineWidth: 0.5)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(shelf.name), \(subtitle)")
    }

    // MARK: - Cover stack

    /// Up to three covers fanned left-to-right; falls back to a single placeholder tile when the
    /// shelf has no cover art.
    private var coverStack: some View {
        let covers = Array(shelf.coverPaths.prefix(3))
        return ZStack(alignment: .leading) {
            if covers.isEmpty {
                placeholderTile(offset: 0)
            } else {
                ForEach(Array(covers.enumerated()), id: \.offset) { index, path in
                    BookCoverImage(coverPath: path)
                        .frame(width: coverSize, height: coverSize)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .shadow(color: .black.opacity(0.15), radius: 3, x: 0, y: 2)
                        .offset(x: CGFloat(index) * 22)
                        .zIndex(Double(covers.count - index))
                }
            }
        }
        .frame(height: coverSize, alignment: .leading)
        .accessibilityHidden(true)
    }

    private func placeholderTile(offset: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: 8)
            .fill(Color.gray.opacity(0.2))
            .frame(width: coverSize, height: coverSize)
            .overlay {
                // Dynamic Type exclusion: fixed-box glyph inside a 64×64 cover placeholder tile
                Image(systemName: "books.vertical.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(.secondary)
            }
            .offset(x: offset)
    }

    // MARK: - Subtitle

    /// "N books" — appends the duration when the shelf reports one.
    private var subtitle: String {
        if shelf.durationLabel.isEmpty {
            String(format: String(localized: "home.shelf_books_count"), shelf.bookCount)
        } else {
            String(
                format: String(localized: "home.shelf_books_count_duration"),
                shelf.bookCount,
                shelf.durationLabel
            )
        }
    }
}

// MARK: - My Shelves row

/// The "My Shelves" section: a localized header over a horizontally-scrolling row of `ShelfCard`s.
/// The caller hides the whole section when there are no shelves.
struct MyShelvesRow: View {
    let shelves: [ShelfItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(String(localized: "home.my_shelves"))
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.primary)
                Spacer()
                NavigationLink(value: ShelfFormDestination(shelfId: nil)) {
                    Image(systemName: "plus")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Color.luTint)
                        .accessibilityLabel(String(localized: "shelf.create_shelf_title"))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(shelves) { shelf in
                        NavigationLink(value: ShelfDestination(id: shelf.id)) {
                            ShelfCard(shelf: shelf)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 20)
            }
        }
    }
}

// MARK: - Preview

extension ShelfItem {
    /// Memberwise initializer for previews — `HomeViewModelWrapper` defines only `init(from:)`,
    /// which suppresses the synthesized one.
    init(id: String, name: String, bookCount: Int, durationLabel: String, coverPaths: [String]) {
        self.id = id
        self.name = name
        self.bookCount = bookCount
        self.durationLabel = durationLabel
        self.coverPaths = coverPaths
    }
}

#Preview("Shelf Cards") {
    ScrollView(.horizontal) {
        HStack(spacing: 14) {
            ShelfCard(shelf: ShelfItem(
                id: "1",
                name: "To Read",
                bookCount: 7,
                durationLabel: "2h 30m",
                coverPaths: ["/a.jpg", "/b.jpg", "/c.jpg"]
            ))
            ShelfCard(shelf: ShelfItem(
                id: "2",
                name: "A Very Long Shelf Name That Truncates Nicely",
                bookCount: 1,
                durationLabel: "",
                coverPaths: []
            ))
        }
        .padding()
    }
}

#Preview("My Shelves Row") {
    MyShelvesRow(shelves: [
        ShelfItem(id: "1", name: "To Read", bookCount: 7, durationLabel: "2h 30m", coverPaths: ["/a.jpg", "/b.jpg"]),
        ShelfItem(id: "2", name: "Favorites", bookCount: 12, durationLabel: "18h 04m", coverPaths: ["/c.jpg"]),
        ShelfItem(id: "3", name: "Mystery", bookCount: 3, durationLabel: "9h 12m", coverPaths: [])
    ])
}

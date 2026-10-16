import SwiftUI

/// A "My Shelves" card (spec §2.7): a 2×2 mosaic of the shelf's covers, its name (with a lock when it is private) and
/// "6 books · 74 hr". `.tile` is the iPhone rail's square card; `.row` is the iPad list's horizontal card. One
/// accessibility element. Pure visual — `MyShelvesSection` wraps it in a `NavigationLink(value:)`.
struct ShelfCard: View {
    enum Style { case tile, row }

    let shelf: ShelfItem
    let style: Style

    static let tileWidth: CGFloat = 150
    static let rowMosaicSide: CGFloat = 76

    private var meta: String { HomeShelfText.meta(bookCount: shelf.bookCount, totalSeconds: shelf.totalSeconds) }

    private var label: String {
        let base = "\(shelf.name), \(meta)"
        return shelf.isPrivate ? base + ", " + String(localized: "home.private_shelf") : base
    }

    var body: some View {
        Group {
            switch style {
            case .tile: tile
            case .row: row
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }

    private var tile: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            CoverMosaic(covers: shelf.covers)
                .padding(Spacing.xs)
                .frame(width: Self.tileWidth, height: Self.tileWidth)
                .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: Radius.m, style: .continuous))
            texts
        }
        .frame(width: Self.tileWidth, alignment: .leading)
        .contentShape(Rectangle())
    }

    private var row: some View {
        HStack(spacing: Spacing.s) {
            CoverMosaic(covers: shelf.covers, spacing: 3)
                .frame(width: Self.rowMosaicSide, height: Self.rowMosaicSide)
            texts
            Spacer(minLength: 0)
        }
        .padding(Spacing.xs)
        .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
        .contentShape(Rectangle())
    }

    private var texts: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Text(shelf.name)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                if shelf.isPrivate {
                    Image(systemName: "lock.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Text(meta)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

// MARK: - My Shelves section

/// The "My Shelves" section (spec §2.7): a header with New Shelf (always there, so the first shelf is one tap away),
/// then the reader's shelves — a scrolling rail of tiles in one column, a grid of row cards when Home is wide — or,
/// with none yet, a line inviting the first. No See all until the My Shelves list exists (open question 1).
struct MyShelvesSection: View {
    let shelves: [ShelfItem]
    let layout: HomeLayout
    /// Whether the section pads its own header and rail to `layout.margin` (false beside the stats card).
    var inset = true

    @State private var isCreatingShelf = false

    private var margin: CGFloat { inset ? layout.margin : 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header.padding(.horizontal, margin)
            if shelves.isEmpty {
                Text(String(localized: "home.shelves_invitation"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, margin)
            } else if layout.isWide {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 220), spacing: 14)], spacing: 14) {
                    ForEach(shelves) { shelf in link(shelf, style: .row) }
                }
                .padding(.horizontal, margin)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 14) {
                        ForEach(shelves) { shelf in link(shelf, style: .tile) }
                    }
                    .padding(.horizontal, margin)
                }
            }
        }
        // A new shelf is a small self-contained task, so it is a sheet over Home rather than a page pushed onto it
        // (HIG, Sheets) — and the sheet brings its own bar.
        .sheet(isPresented: $isCreatingShelf) {
            CreateEditShelfView(shelfId: nil)
        }
    }

    private var header: some View {
        HStack {
            Text(String(localized: "home.my_shelves_ios"))
                .font(.title3.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button {
                isCreatingShelf = true
            } label: {
                Image(systemName: "plus")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Color.luTint)
                    // The glyph draws ~20pt; the finger gets 44.
                    .minimumTapTarget(visualSize: 20)
                    .accessibilityLabel(String(localized: "home.new_shelf_ios"))
            }
            .buttonStyle(.plain)
        }
    }

    private func link(_ shelf: ShelfItem, style: ShelfCard.Style) -> some View {
        NavigationLink(value: ShelfDestination(id: shelf.id)) {
            ShelfCard(shelf: shelf, style: style)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Preview

extension ShelfItem {
    /// Memberwise initializer for previews — `HomeViewModelWrapper` defines only `init(from:)`,
    /// which suppresses the synthesized one.
    init(
        id: String,
        name: String,
        bookCount: Int,
        durationLabel: String,
        covers: [DiscoverShelfCover] = [],
        isPrivate: Bool = false,
        totalSeconds: Int = 0
    ) {
        self.id = id
        self.name = name
        self.bookCount = bookCount
        self.durationLabel = durationLabel
        self.covers = covers
        self.isPrivate = isPrivate
        self.totalSeconds = totalSeconds
    }
}

private let previewShelves = [
    ShelfItem(id: "1", name: "To Read", bookCount: 7, durationLabel: "", totalSeconds: 9_000),
    ShelfItem(id: "2", name: "Favorites", bookCount: 12, durationLabel: "", isPrivate: true, totalSeconds: 65_040),
    ShelfItem(id: "3", name: "Mystery", bookCount: 1, durationLabel: "")
]

#Preview("Shelf cards") {
    VStack(alignment: .leading, spacing: 16) {
        ShelfCard(shelf: previewShelves[0], style: .tile)
        ShelfCard(shelf: previewShelves[1], style: .row)
    }
    .padding()
}

#Preview("My Shelves, iPhone") {
    NavigationStack {
        MyShelvesSection(shelves: previewShelves, layout: .forWidth(390))
    }
}

#Preview("My Shelves, iPad") {
    NavigationStack {
        MyShelvesSection(shelves: previewShelves, layout: .forWidth(1032))
    }
}

#Preview("My Shelves, none yet") {
    NavigationStack {
        MyShelvesSection(shelves: [], layout: .forWidth(390))
    }
}

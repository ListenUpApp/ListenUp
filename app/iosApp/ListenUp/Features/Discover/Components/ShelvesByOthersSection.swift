import SwiftUI

/// Shelves by Others: other readers' public shelves, grouped under whoever made them (spec X17–X19).
/// Each owner is a link to their profile; each shelf a link to the shelf, with its cover mosaic, name
/// and count. A rail of shelves per owner at compact width; at regular width owners sit side by side,
/// each with an adaptive grid of their shelves.
///
/// Loading shows a spinner; error and empty show one secondary line (PR 6 owns their copy).
struct ShelvesByOthersSection: View {
    let phase: DiscoverShelvesPhase
    let horizontalInset: CGFloat
    let isRegular: Bool

    /// A shelf card's width in the compact rail.
    private let railCardWidth: CGFloat = 150

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            DiscoverSectionHeader(title: String(localized: "discover.shelves_by_others_ios"))
                .padding(.horizontal, horizontalInset)

            content
        }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.xl)
        case .ready(let owners):
            if owners.isEmpty {
                message(String(localized: "discover.no_shelves_to_discover_yet"))
            } else if isRegular {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 360), spacing: 22, alignment: .top)],
                    alignment: .leading,
                    spacing: Spacing.xl
                ) {
                    ForEach(owners) { owner in
                        VStack(alignment: .leading, spacing: Spacing.s) {
                            ownerHeader(owner)
                            LazyVGrid(
                                columns: [GridItem(.adaptive(minimum: 120), spacing: 14, alignment: .top)],
                                alignment: .leading,
                                spacing: 14
                            ) {
                                ForEach(owner.shelves) { shelf in shelfCard(shelf, width: nil) }
                            }
                        }
                    }
                }
                .padding(.horizontal, horizontalInset)
            } else {
                VStack(alignment: .leading, spacing: Spacing.l) {
                    ForEach(owners) { owner in
                        VStack(alignment: .leading, spacing: Spacing.s) {
                            ownerHeader(owner)
                                .padding(.horizontal, horizontalInset)
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(alignment: .top, spacing: Spacing.m) {
                                    ForEach(owner.shelves) { shelf in shelfCard(shelf, width: railCardWidth) }
                                }
                                .padding(.horizontal, horizontalInset)
                            }
                        }
                    }
                }
            }
        case .error:
            message(String(localized: "discover.no_shelves_to_discover_yet"))
        }
    }

    private func ownerHeader(_ owner: DiscoverShelfOwnerRow) -> some View {
        NavigationLink(value: ProfileDestination(userId: owner.id)) {
            HStack(spacing: Spacing.xs) {
                UserAvatarView(userId: owner.id, fallbackName: owner.displayName, size: 32)
                    .accessibilityHidden(true)
                Text(owner.displayName)
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(Self.shelfCount(owner.shelves.count))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }

    private func shelfCard(_ shelf: DiscoverShelfRow, width: CGFloat?) -> some View {
        NavigationLink(value: ShelfDestination(id: shelf.id)) {
            VStack(alignment: .leading, spacing: Spacing.xxs) {
                ShelfCoverMosaic(covers: shelf.covers)
                Text(shelf.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(Self.bookCount(shelf.bookCount))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(width: width, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressScaleCard)
        .accessibilityElement(children: .combine)
    }

    /// "1 shelf" / "2 shelves".
    static func shelfCount(_ count: Int) -> String {
        let format = count == 1
            ? String(localized: "discover.shelf_count")
            : String(localized: "discover.shelves_count")
        return String(format: format, Int32(count))
    }

    /// "1 book" / "8 books".
    static func bookCount(_ count: Int) -> String {
        let format = count == 1
            ? String(localized: "common.book_count")
            : String(localized: "common.books_count")
        return String(format: format, Int32(count))
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .padding(.horizontal, horizontalInset)
            .padding(.vertical, Spacing.s)
    }
}

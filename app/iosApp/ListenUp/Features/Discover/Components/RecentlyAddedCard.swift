import SwiftUI

/// A Recently Added card: a rounded cover with the title, the author and when it was added
/// ("Added 2 days ago") beneath it. Tapping navigates to the book's detail. `width` nil fills the grid
/// cell it is given.
///
/// When `selection` is supplied and selecting, the cover shows a selection circle and a tap toggles
/// selection instead of navigating; a long-press is the secondary entry. `nil` = navigation only.
struct RecentlyAddedCard: View {
    let book: RecentlyAddedBook
    let width: CGFloat?
    var selection: BookSelectionObserver?

    @Environment(\.restrictedBooks) private var restrictedBooks

    private var addedLabel: String {
        DiscoverCopy.added(book.addedAt, relativeTo: Date())
    }

    var body: some View {
        SelectableBookCard(bookId: book.id, selection: selection) {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                BookCoverImage(bookId: book.id, coverPath: book.coverPath, coverHash: book.coverHash)
                    .aspectRatio(1, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.l))
                    .restrictedMarker(bookId: book.id, isSelecting: selection?.isSelecting ?? false)
                    .coverHoverEffect(cornerRadius: Radius.l)
                    .shadow(color: .black.opacity(0.12), radius: 6, x: 0, y: 3)
                    .bookSelectionCircle(bookId: book.id, selection: selection)

                VStack(alignment: .leading, spacing: 2) {
                    Text(book.title)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)

                    if let author = book.author, !author.isEmpty {
                        Text(author)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }

                    Text(addedLabel)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(width: width, alignment: .leading)
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            RestrictedMarker.label(
                [book.title, book.author].compactMap { $0 }.joined(separator: ", "),
                isRestricted: restrictedBooks?.isRestricted(book.id) == true
            )
        )
        .accessibilityValue(addedLabel)
    }
}

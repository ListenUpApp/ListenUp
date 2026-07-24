import SwiftUI

/// A cover card in the "Recently Added" rail: a rounded cover with the title below and a
/// calmer relative "added" caption ("2d ago"). Tapping navigates to the book's detail.
/// Width is width-driven by the caller (wider on iPad) so the rail reads as a real layout
/// at every size.
///
/// When `selection` is supplied and selecting, the cover shows a selection circle and a tap toggles
/// selection instead of navigating; a long-press is the secondary entry. `nil` = navigation only.
struct RecentlyAddedCard: View {
    let book: RecentlyAddedBook
    let width: CGFloat
    var selection: BookSelectionObserver?

    private static let relativeFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter
    }()

    private var addedLabel: String {
        Self.relativeFormatter.localizedString(for: book.addedAt, relativeTo: Date())
    }

    var body: some View {
        SelectableBookCard(bookId: book.id, selection: selection) {
            VStack(alignment: .leading, spacing: 8) {
                BookCoverImage(bookId: book.id, coverPath: book.coverPath, coverHash: book.coverHash)
                    .frame(width: width, height: width)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .shadow(color: .black.opacity(0.12), radius: 6, x: 0, y: 3)
                    .bookSelectionCircle(bookId: book.id, selection: selection)

                VStack(alignment: .leading, spacing: 2) {
                    Text(book.title)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)

                    Text(addedLabel)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: width, alignment: .leading)
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(book.title)
        .accessibilityValue(addedLabel)
    }
}

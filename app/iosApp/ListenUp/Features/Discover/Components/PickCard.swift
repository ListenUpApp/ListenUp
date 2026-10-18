import SwiftUI

/// A Something New pick: the cover, the title, the author, which book of its series it is, and how
/// long it runs (spec X6, X7). Tapping opens the book. `width` nil fills the grid cell it is given.
///
/// When `selection` is supplied and selecting, the cover shows a selection circle and a tap toggles
/// selection instead of navigating; a long-press is the secondary entry. `nil` = navigation only.
struct PickCard: View {
    let book: DiscoverBook
    let width: CGFloat?
    var selection: BookSelectionObserver?

    @Environment(\.restrictedBooks) private var restrictedBooks

    private var seriesLine: String { DiscoverCopy.seriesLine(series: book.seriesName, sequence: book.sequenceLabel) }
    private var length: String { DiscoverCopy.length(ms: book.durationMs) }

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
                        captionLine(author)
                    }
                    captionLine(seriesLine)
                    captionLine(length)
                }
            }
            .frame(width: width, alignment: .leading)
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            RestrictedMarker.label(
                [book.title, book.author, seriesLine, length].compactMap { $0 }.joined(separator: ", "),
                isRestricted: restrictedBooks?.isRestricted(book.id) == true
            )
        )
    }

    private func captionLine(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(.secondary)
            .lineLimit(1)
    }
}

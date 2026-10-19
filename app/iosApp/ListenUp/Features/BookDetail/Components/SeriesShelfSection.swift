import SwiftUI

/// The book's series as square covers in sequence (spec §3.2.2): this book outlined, and a line that says where to go —
/// "Book 1 of 5 · start here" before starting, "Next: book 2" once finished, "Book 3 of 5" otherwise. Each cover opens
/// its book; the heading opens the series.
struct SeriesShelfSection: View {
    let shelf: BookSeriesShelf
    let stage: BookReadingStage

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            NavigationLink(value: SeriesDestination(id: shelf.seriesId)) {
                HStack(spacing: Spacing.xxs) {
                    Text(String(format: String(localized: "book.detail_series_heading"), shelf.name))
                        .font(.title2.bold())
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.leading)
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(.isHeader)
            .padding(.horizontal, Spacing.xxs)

            VStack(alignment: .leading, spacing: 12) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(shelf.books) { book in
                            NavigationLink(value: BookDestination(id: book.id)) {
                                cover(book)
                            }
                            .buttonStyle(.pressScaleCard)
                            .accessibilityLabel(label(for: book))
                            .accessibilityAddTraits(book.isThis ? .isSelected : [])
                        }
                    }
                    .padding(4)
                }
                Text(Self.caption(shelf, stage: stage))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func cover(_ book: BookSeriesShelfItem) -> some View {
        BookCoverImage(bookId: book.id, coverPath: book.coverPath, coverHash: book.coverHash)
            .frame(width: 64, height: 64)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                if book.isThis {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.listenUpOrange, lineWidth: 2)
                        .padding(-3)
                }
            }
    }

    private func label(for book: BookSeriesShelfItem) -> String {
        let place = Self.place(of: book, in: shelf)
        return book.isThis
            ? String(format: String(localized: "book.detail_series_cover_this_a11y"), place)
            : String(format: String(localized: "book.detail_series_cover_a11y"), place)
    }

    /// Where this book sits, and where to go next.
    static func caption(_ shelf: BookSeriesShelf, stage: BookReadingStage) -> String {
        let thisPlace = place(of: shelf.books[shelf.thisIndex], in: shelf)
        let next = shelf.nextBookId.flatMap { id in shelf.books.first { $0.id == id } }
        if stage == .notStarted, shelf.thisIndex == 0 {
            return String(format: String(localized: "book.detail_series_start_here"), thisPlace, shelf.books.count)
        }
        if stage == .finished, let next {
            return String(format: String(localized: "book.detail_series_next"), place(of: next, in: shelf))
        }
        return String(format: String(localized: "book.detail_series_position"), thisPlace, shelf.books.count)
    }

    /// The book's place as a person writes it, or its position on the shelf when it is unnumbered.
    private static func place(of book: BookSeriesShelfItem, in shelf: BookSeriesShelf) -> String {
        book.sequenceLabel ?? String((shelf.books.firstIndex(of: book) ?? 0) + 1)
    }
}

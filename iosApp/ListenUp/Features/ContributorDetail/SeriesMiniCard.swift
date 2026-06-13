import SwiftUI
@preconcurrency import Shared

/// Compact series card for the contributor "Series" grid: an overlapping cover stack,
/// the series name, and a book count, on a rounded surface. Navigates to the series.
struct SeriesMiniCard: View {
    let series: SeriesWithBooks_

    private var seriesId: String { String(describing: series.series.id) }
    private var books: [BookListItem] { Array(series.books) }

    var body: some View {
        NavigationLink(value: SeriesDestination(id: seriesId)) {
            HStack(spacing: 13) {
                CoverStack(books: books, size: 52, peek: 12, maxCovers: 3)
                VStack(alignment: .leading, spacing: 1) {
                    Text(series.series.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)
                    Text(bookCountLabel)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luLabel3)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.luSurface2))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Color.luSeparator, lineWidth: 0.5))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(series.series.name)
    }

    private var bookCountLabel: String {
        let count = books.count
        let noun = count == 1 ? String(localized: "common.book") : String(localized: "common.books")
        return "\(count) \(noun)"
    }
}

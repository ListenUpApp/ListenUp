import SwiftUI
@preconcurrency import Shared

/// Standalone series row card for the iPhone Series list: an overlapping cover stack,
/// name + meta, a progress affordance, and a chevron — its own rounded surface.
struct SeriesRowCard: View {
    let series: SeriesWithBooks
    let progress: SeriesProgressState

    private var seriesId: String { series.series.idString }
    private var books: [BookListItem] { Array(series.books) }
    private var meta: String {
        let count = books.count
        let author = books.first?.authors.first?.name
        let booksText = "\(count) \(count == 1 ? String(localized: "common.book") : String(localized: "common.books"))"
        return author.map { "\(booksText) · \($0)" } ?? booksText
    }

    var body: some View {
        NavigationLink(value: SeriesDestination(id: seriesId)) {
            HStack(spacing: 16) {
                CoverStack(books: books, size: 76, peek: 17)
                VStack(alignment: .leading, spacing: 2) {
                    Text(series.series.name)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(meta)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                        .lineLimit(1)
                    SeriesProgressBadge(state: progress).padding(.top, 9)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luLabel3)
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.luSurface2))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.luSeparator, lineWidth: 0.5))
        }
        .buttonStyle(.pressScaleCard)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(series.series.name)
    }
}

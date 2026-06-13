import SwiftUI
@preconcurrency import Shared

/// Series grid card for the iPad Series list: a centered cover stack over name + meta
/// + progress, on its own rounded surface.
struct SeriesGridCard: View {
    let series: SeriesWithBooks_
    let progress: SeriesProgressState

    private var seriesId: String { String(describing: series.series.id) }
    private var books: [BookListItem] { Array(series.books) }
    private var meta: String {
        let count = books.count
        let author = books.first?.authors.first?.name
        let booksText = "\(count) \(count == 1 ? String(localized: "common.book") : String(localized: "common.books"))"
        return author.map { "\(booksText) · \($0)" } ?? booksText
    }

    var body: some View {
        NavigationLink(value: SeriesDestination(id: seriesId)) {
            VStack(alignment: .leading, spacing: 16) {
                CoverStack(books: books, size: 112, peek: 26, maxCovers: 5)
                    .frame(maxWidth: .infinity, alignment: .center)
                VStack(alignment: .leading, spacing: 2) {
                    Text(series.series.name).font(.headline).foregroundStyle(.primary).lineLimit(1)
                    Text(meta).font(.footnote).foregroundStyle(Color.luLabel2).lineLimit(1)
                }
                SeriesProgressBadge(state: progress)
            }
            .padding(22)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color.luSurface2))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Color.luSeparator, lineWidth: 0.5))
        }
        .buttonStyle(.pressScaleCard)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(series.series.name)
    }
}

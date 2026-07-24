import SwiftUI
import Shared

/// Series grid card for the iPad Series list: a centered cover stack over name + meta
/// + progress, on its own rounded surface.
struct SeriesGridCard: View {
    let series: SeriesRow
    let progress: SeriesProgressState

    private var meta: String {
        let count = series.bookCount
        let booksText = "\(count) \(count == 1 ? String(localized: "common.book") : String(localized: "common.books"))"
        return series.authorName.map { "\(booksText) · \($0)" } ?? booksText
    }

    var body: some View {
        NavigationLink(value: SeriesDestination(id: series.id)) {
            VStack(alignment: .leading, spacing: 16) {
                CoverStack(covers: series.covers, size: 112, peek: 26, maxCovers: 5)
                    .frame(maxWidth: .infinity, alignment: .center)
                VStack(alignment: .leading, spacing: 2) {
                    Text(series.name).font(.headline).foregroundStyle(.primary).lineLimit(1)
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
        .accessibilityLabel(series.name)
    }
}

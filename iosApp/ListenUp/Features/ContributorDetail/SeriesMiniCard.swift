import SwiftUI

/// Compact series card for the contributor "Series" grid: an overlapping cover stack,
/// the series name, and a book count, on a rounded surface. Navigates to the series.
struct SeriesMiniCard: View {
    let series: SeriesRow

    var body: some View {
        NavigationLink(value: SeriesDestination(id: series.id)) {
            HStack(spacing: 13) {
                CoverStack(covers: series.covers, size: 52, peek: 12, maxCovers: 3)
                VStack(alignment: .leading, spacing: 1) {
                    Text(series.name)
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
        .accessibilityLabel(series.name)
    }

    private var bookCountLabel: String {
        let count = series.bookCount
        let noun = count == 1 ? String(localized: "common.book") : String(localized: "common.books")
        return "\(count) \(noun)"
    }
}

import SwiftUI

/// A row of stat groups (value over label) split by vertical hairline dividers — used
/// on the series and contributor detail screens. `centered` (default) for the phone
/// detail header; `centered: false` (leading) for the iPad spotlight.
struct StatStrip: View {
    struct Stat: Identifiable {
        let value: String
        let label: String
        /// Stable identity: the label is unique within a strip (Books/Finished/Total).
        var id: String { label }

        init(value: String, label: String) {
            self.value = value
            self.label = label
        }
    }

    let stats: [Stat]
    var centered: Bool = true

    @Environment(\.displayScale) private var displayScale
    @ScaledMetric private var dividerHeight: CGFloat = 30

    private var groupAlignment: HorizontalAlignment { centered ? .center : .leading }
    private var hairline: CGFloat { 1 / max(displayScale, 1) }

    /// One row while every stat fits on a line; otherwise two per row, then one per row, so a
    /// large text size regroups the stats instead of breaking their labels mid-word (HIG,
    /// Typography: "Make sure your app's layout adapts to all font sizes.").
    var body: some View {
        ViewThatFits(in: .horizontal) {
            row
            grid(columns: 2)
            grid(columns: 1)
        }
        .frame(maxWidth: centered ? .infinity : nil, alignment: centered ? .center : .leading)
    }

    private var row: some View {
        HStack(spacing: 20) {
            ForEach(Array(stats.enumerated()), id: \.element.id) { index, stat in
                cell(stat)
                if index < stats.count - 1 {
                    Rectangle()
                        .fill(Color.luSeparator)
                        .frame(width: hairline, height: dividerHeight)
                        .accessibilityHidden(true)
                }
            }
        }
    }

    private func grid(columns: Int) -> some View {
        Grid(alignment: centered ? .center : .leading, horizontalSpacing: 20, verticalSpacing: 12) {
            ForEach(Array(Self.rows(of: stats, columns: columns).enumerated()), id: \.offset) { _, rowStats in
                GridRow {
                    ForEach(rowStats) { cell($0) }
                }
            }
        }
    }

    private func cell(_ stat: Stat) -> some View {
        VStack(alignment: groupAlignment, spacing: 2) {
            Text(stat.value)
                .font(.title3.weight(.bold))
                .monospacedDigit()
                .foregroundStyle(.primary)
            Text(stat.label)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(stat.value) \(stat.label)")
    }

    /// The stats in order, `columns` to a row; the last row holds the remainder.
    static func rows(of stats: [Stat], columns: Int) -> [[Stat]] {
        let width = max(columns, 1)
        return stride(from: 0, to: stats.count, by: width).map {
            Array(stats[$0 ..< min($0 + width, stats.count)])
        }
    }
}

// MARK: - Preview

#Preview("StatStrip") {
    VStack(spacing: 40) {
        StatStrip(stats: [
            .init(value: "5", label: "Books"),
            .init(value: "2", label: "Finished"),
            .init(value: "203h", label: "Total")
        ])
        StatStrip(stats: [
            .init(value: "42", label: "Books"),
            .init(value: "318h", label: "Listened")
        ], centered: false)
        .padding(.horizontal)
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}

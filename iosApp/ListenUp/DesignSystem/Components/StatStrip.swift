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

    var body: some View {
        HStack(spacing: 20) {
            ForEach(Array(stats.enumerated()), id: \.element.id) { index, stat in
                VStack(alignment: groupAlignment, spacing: 2) {
                    Text(stat.value)
                        .font(.title3.weight(.bold))
                        .monospacedDigit()
                        .foregroundStyle(.primary)
                    Text(stat.label)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(stat.value) \(stat.label)")
                if index < stats.count - 1 {
                    Rectangle()
                        .fill(Color.luSeparator)
                        .frame(width: hairline, height: dividerHeight)
                        .accessibilityHidden(true)
                }
            }
        }
        .frame(maxWidth: centered ? .infinity : nil, alignment: centered ? .center : .leading)
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

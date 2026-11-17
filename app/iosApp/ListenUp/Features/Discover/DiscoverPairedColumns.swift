import SwiftUI

/// Regular-width Discover's paired row: the Leaderboard and Activity side by side, top-aligned, sharing
/// the width in proportion to `weights` — the board's `minmax(0, 1fr) minmax(0, 1.15fr)`, so the
/// sentences get a little more room than the ranks.
///
/// Regular width is not always wide: an iPad mini in portrait, or an app sharing the screen, would give
/// each column too little for a name or a sentence. When the narrower column would fall below
/// `minColumnWidth` the two stack instead, full width, as they do on an iPhone.
struct DiscoverPairedColumns: Layout {
    /// The Leaderboard : Activity split.
    static let boardWeights: [CGFloat] = [1, 1.15]
    /// The narrowest a paired column may be: an iPhone's, with room for a leaderboard name.
    static let minColumnWidth: CGFloat = 320

    var weights: [CGFloat] = Self.boardWeights
    var spacing: CGFloat
    /// The gap between the two when they stack.
    var stackSpacing: CGFloat

    /// Pure: each column's width out of `total`, after the gaps, in proportion to its weight.
    static func columnWidths(total: CGFloat, spacing: CGFloat, weights: [CGFloat]) -> [CGFloat] {
        let weightSum = weights.reduce(0, +)
        guard weightSum > 0 else { return weights.map { _ in 0 } }
        let available = max(total - spacing * CGFloat(max(weights.count - 1, 0)), 0)
        return weights.map { available * $0 / weightSum }
    }

    /// Pure: whether `total` leaves every column at least `minColumnWidth`, so they can sit side by side.
    static func pairs(total: CGFloat, spacing: CGFloat, weights: [CGFloat]) -> Bool {
        let widths = columnWidths(total: total, spacing: spacing, weights: weights)
        return (widths.min() ?? 0) >= minColumnWidth
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let total = proposal.width ?? subviews.reduce(0) { $0 + $1.sizeThatFits(.unspecified).width }
        let widths = widths(total: total, count: subviews.count)
        guard isPaired(total: total, count: subviews.count) else {
            let heights = subviews.map { $0.sizeThatFits(ProposedViewSize(width: total, height: nil)).height }
            let gaps = stackSpacing * CGFloat(max(subviews.count - 1, 0))
            return CGSize(width: total, height: heights.reduce(0, +) + gaps)
        }
        let height = zip(subviews, widths)
            .map { subview, width in subview.sizeThatFits(ProposedViewSize(width: width, height: nil)).height }
            .max() ?? 0
        return CGSize(width: total, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard isPaired(total: bounds.width, count: subviews.count) else {
            var y = bounds.minY
            for subview in subviews {
                let size = ProposedViewSize(width: bounds.width, height: nil)
                subview.place(at: CGPoint(x: bounds.minX, y: y), anchor: .topLeading, proposal: size)
                y += subview.sizeThatFits(size).height + stackSpacing
            }
            return
        }
        var x = bounds.minX
        for (subview, width) in zip(subviews, widths(total: bounds.width, count: subviews.count)) {
            subview.place(
                at: CGPoint(x: x, y: bounds.minY),
                anchor: .topLeading,
                proposal: ProposedViewSize(width: width, height: nil)
            )
            x += width + spacing
        }
    }

    private func isPaired(total: CGFloat, count: Int) -> Bool {
        Self.pairs(total: total, spacing: spacing, weights: paddedWeights(count: count))
    }

    /// Weights for `count` subviews: the declared ones, padded with 1 for any extra subview.
    private func paddedWeights(count: Int) -> [CGFloat] {
        Array(weights.prefix(count)) + Array(repeating: 1, count: max(count - weights.count, 0))
    }

    private func widths(total: CGFloat, count: Int) -> [CGFloat] {
        Self.columnWidths(total: total, spacing: spacing, weights: paddedWeights(count: count))
    }
}

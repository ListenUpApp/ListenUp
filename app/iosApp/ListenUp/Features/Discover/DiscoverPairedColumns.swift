import SwiftUI

/// Regular-width Discover's paired row: the Leaderboard and Activity side by side, top-aligned, sharing
/// the width in proportion to `weights` — the board's `minmax(0, 1fr) minmax(0, 1.15fr)`, so the
/// sentences get a little more room than the ranks (spec §3.7).
struct DiscoverPairedColumns: Layout {
    /// The Leaderboard : Activity split.
    static let boardWeights: [CGFloat] = [1, 1.15]

    var weights: [CGFloat] = Self.boardWeights
    var spacing: CGFloat

    /// Pure: each column's width out of `total`, after the gaps, in proportion to its weight.
    static func columnWidths(total: CGFloat, spacing: CGFloat, weights: [CGFloat]) -> [CGFloat] {
        let weightSum = weights.reduce(0, +)
        guard weightSum > 0 else { return weights.map { _ in 0 } }
        let available = max(total - spacing * CGFloat(max(weights.count - 1, 0)), 0)
        return weights.map { available * $0 / weightSum }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let total = proposal.width ?? subviews.reduce(0) { $0 + $1.sizeThatFits(.unspecified).width }
        let widths = widths(total: total, count: subviews.count)
        let height = zip(subviews, widths)
            .map { subview, width in subview.sizeThatFits(ProposedViewSize(width: width, height: nil)).height }
            .max() ?? 0
        return CGSize(width: total, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
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

    /// Weights for `count` subviews: the declared ones, padded with 1 for any extra subview.
    private func widths(total: CGFloat, count: Int) -> [CGFloat] {
        let padded = Array(weights.prefix(count)) + Array(repeating: 1, count: max(count - weights.count, 0))
        return Self.columnWidths(total: total, spacing: spacing, weights: padded)
    }
}

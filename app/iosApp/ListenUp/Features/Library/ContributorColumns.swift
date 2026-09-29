import CoreGraphics

/// Pure layout math for the responsive Contributors list: how many columns fit a width. Kept
/// separate from the view so the responsive behavior is unit-tested.
enum ContributorColumns {
    /// Columns that fit `availableWidth` at a comfortable minimum column width, clamped to `[1, maxColumns]`.
    static func columnCount(availableWidth: CGFloat, minColumnWidth: CGFloat = 360, maxColumns: Int = 3) -> Int {
        guard availableWidth > 0, minColumnWidth > 0 else { return 1 }
        let fit = Int(availableWidth / minColumnWidth)
        return min(maxColumns, max(1, fit))
    }
}

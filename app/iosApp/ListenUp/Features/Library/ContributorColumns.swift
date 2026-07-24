import CoreGraphics

/// Pure layout math for the responsive multi-column Contributors list: how many columns fit a
/// width, and how to split an ordered list into balanced contiguous columns. Kept separate from
/// the view so the responsive behavior is unit-tested.
enum ContributorColumns {
    /// Columns that fit `availableWidth` at a comfortable minimum column width, clamped to `[1, maxColumns]`.
    static func columnCount(availableWidth: CGFloat, minColumnWidth: CGFloat = 360, maxColumns: Int = 3) -> Int {
        guard availableWidth > 0, minColumnWidth > 0 else { return 1 }
        let fit = Int(availableWidth / minColumnWidth)
        return min(maxColumns, max(1, fit))
    }

    /// Partition ordered `items` into `columns` contiguous runs, balancing the summed `weight`
    /// per run. Greedy: fill toward `totalWeight / columns`, never reordering. Order is preserved.
    /// If `items` is shorter than `columns`, trailing columns are empty. Every input item appears once.
    static func balancedColumns<T>(_ items: [T], weight: (T) -> Int, columns: Int) -> [[T]] {
        guard columns > 1, !items.isEmpty else {
            return columns <= 1 ? [items] : [items] + Array(repeating: [], count: columns - 1)
        }
        let total = items.reduce(0) { $0 + weight($1) }
        let target = Double(total) / Double(columns)

        var result: [[T]] = []
        var current: [T] = []
        var currentWeight = 0

        for (index, item) in items.enumerated() {
            current.append(item)
            currentWeight += weight(item)
            let itemsRemaining = items.count - index - 1
            let columnsAfterThis = columns - result.count - 1
            // Split when the current run hit its weight target, or when the remaining items can
            // no longer afford to share a column (each must claim its own to fill every column).
            let mustReserveForRemaining = itemsRemaining <= columnsAfterThis
            if result.count < columns - 1,
               itemsRemaining >= 1,
               Double(currentWeight) >= target || mustReserveForRemaining {
                result.append(current)
                current = []
                currentWeight = 0
            }
        }
        result.append(current)
        while result.count < columns { result.append([]) }
        return result
    }
}

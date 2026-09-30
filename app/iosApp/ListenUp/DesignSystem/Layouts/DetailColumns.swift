import SwiftUI

/// How a detail screen (book, series, contributor, profile) spends the width it is given: one
/// stacked column, or a rail (the hero and its actions) beside a flexible main column.
///
/// Width-driven, not size-class-driven (iosApp rule 12). An iPad reports `.regular` for a
/// 1/2-width landscape window that is too narrow for two columns, and `.compact` in a 1/3 Split View
/// that is not; only the real width answers the question. HIG, Layout: "Design a layout that adapts
/// gracefully and consistently … when they … resize a window". The rail grows with the window —
/// a fixed 320pt rail was a phone column stranded on a 13-inch display.
enum DetailColumns: Equatable {
    case stacked
    case split(railWidth: CGFloat)

    /// The rail's share of the width, and the band it stays inside.
    static let railFraction: CGFloat = 0.34
    static let railRange: ClosedRange<CGFloat> = 280 ... 380
    /// The narrowest main column worth splitting for: room for a chapter row or a book row.
    static let minimumMainWidth: CGFloat = 360
    /// Space between the rail and the main column.
    static let gutter: CGFloat = 40
    /// The screen's side margin, each side.
    static let margin: CGFloat = 24

    /// The columns for a screen `width` points wide.
    static func forWidth(_ width: CGFloat) -> DetailColumns {
        let rail = railWidth(forWidth: width)
        let main = width - rail - gutter - 2 * margin
        return main >= minimumMainWidth ? .split(railWidth: rail) : .stacked
    }

    /// The rail for a screen `width` points wide: a share of the width, kept inside `railRange`.
    static func railWidth(forWidth width: CGFloat) -> CGFloat {
        min(max(width * railFraction, railRange.lowerBound), railRange.upperBound).rounded()
    }

    /// The first frame's guess, before the width has been measured: the size class is the only
    /// signal then, and guessing right avoids a one-frame flash of the wrong layout.
    static func estimate(horizontalSizeClass: UserInterfaceSizeClass?) -> DetailColumns {
        forWidth(horizontalSizeClass == .regular ? 1024 : 390)
    }
}

/// Measures the width a detail screen is offered and hands its content the `DetailColumns` for it.
struct DetailColumnsReader<Content: View>: View {
    @ViewBuilder let content: (DetailColumns) -> Content

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var measuredWidth: CGFloat?

    private var columns: DetailColumns {
        guard let measuredWidth, measuredWidth > 0 else {
            return DetailColumns.estimate(horizontalSizeClass: horizontalSizeClass)
        }
        return DetailColumns.forWidth(measuredWidth)
    }

    var body: some View {
        content(columns)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { measuredWidth = $0 }
    }
}

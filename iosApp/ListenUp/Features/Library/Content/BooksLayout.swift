import CoreGraphics

/// The per-size-class layout decisions for the Books grid (`LibraryPad` at regular width,
/// the phone layout at compact). Pure and `Equatable` so the adaptive contract is
/// unit-tested rather than buried in view code.
struct BooksLayout: Equatable {
    let sideMargin: CGFloat
    let gridSpacing: CGFloat
    /// Regular width shows an inline `SortRow`; compact keeps the `FloatingSortButton` overlay.
    let usesInlineSort: Bool
    /// The trailing alphabet index — kept on phone, dropped on iPad where the wider grid makes it redundant.
    let showsScrubber: Bool

    static func forRegularWidth(_ isRegular: Bool) -> BooksLayout {
        isRegular
            ? BooksLayout(sideMargin: 36, gridSpacing: 24, usesInlineSort: true, showsScrubber: false)
            : BooksLayout(sideMargin: 16, gridSpacing: 16, usesInlineSort: false, showsScrubber: true)
    }
}

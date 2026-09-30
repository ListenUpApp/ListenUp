import CoreGraphics

/// The per-size-class layout decisions for the Books grid (`LibraryPad` at regular width,
/// the phone layout at compact). Pure and `Equatable` so the adaptive contract is
/// unit-tested rather than buried in view code.
struct BooksLayout: Equatable {
    let sideMargin: CGFloat
    let gridSpacing: CGFloat
    /// The trailing alphabet index. Shown at every width: a wider grid holds more books per row but
    /// a large library is still hundreds of rows deep, and the index is how a keyboardless iPad
    /// jumps to "T" (HIG, Designing for iPadOS: don't ship less navigation on the larger display).
    let showsScrubber: Bool

    static func forRegularWidth(_ isRegular: Bool) -> BooksLayout {
        isRegular
            ? BooksLayout(sideMargin: 36, gridSpacing: 24, showsScrubber: true)
            : BooksLayout(sideMargin: 16, gridSpacing: 16, showsScrubber: true)
    }
}

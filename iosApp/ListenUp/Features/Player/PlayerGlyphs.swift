import Foundation

/// Pure mapping from a skip interval (seconds) to the SF Symbol that depicts it.
///
/// iOS ships dedicated `goforward.X` / `gobackward.X` glyphs for a fixed set of
/// intervals; any other value falls back to the generic `goforward` / `gobackward`
/// so the control always renders. Kept free of SwiftUI so the branches are
/// unit-testable in isolation.
enum PlayerGlyphs {
    /// Intervals iOS provides a numbered skip glyph for.
    private static let dedicatedIntervals: Set<Int> = [5, 10, 15, 30, 45, 60, 75, 90]

    /// SF Symbol name for a forward skip of `seconds`.
    static func skipForward(seconds: Int) -> String {
        dedicatedIntervals.contains(seconds) ? "goforward.\(seconds)" : "goforward"
    }

    /// SF Symbol name for a backward skip of `seconds`.
    static func skipBackward(seconds: Int) -> String {
        dedicatedIntervals.contains(seconds) ? "gobackward.\(seconds)" : "gobackward"
    }
}

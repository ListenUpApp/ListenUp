import CoreGraphics
import Testing
@testable import ListenUp

/// How far a small control's hit area reaches past what it draws.
@Suite("Tap target")
struct TapTargetTests {
    @Test func theMinimumIsApplesFortyFourPoints() {
        #expect(TapTarget.minimum == 44)
    }

    /// A 20pt glyph reaches 12pt past each edge, so the hit area is 44 across.
    @Test func aSmallControlIsWidenedEvenlyToTheMinimum() {
        #expect(TapTarget.outset(forVisualSize: 20) == 12)
        #expect(TapTarget.outset(forVisualSize: 26) == 9)
        #expect(TapTarget.outset(forVisualSize: 34) == 5)
    }

    /// Nothing to widen: a control already at or past the minimum keeps its own hit area.
    @Test func aControlAtOrAboveTheMinimumIsLeftAlone() {
        #expect(TapTarget.outset(forVisualSize: 44) == 0)
        #expect(TapTarget.outset(forVisualSize: 60) == 0)
    }
}

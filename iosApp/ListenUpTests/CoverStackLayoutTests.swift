import CoreGraphics
import Testing
@testable import ListenUp

@Suite("CoverStackLayout")
struct CoverStackLayoutTests {
    @Test func totalWidthForEmptyDeckIsFrontSize() {
        let layout = CoverStackLayout(coverCount: 0, size: 100, peek: 20)
        #expect(layout.totalWidth == 100)
    }

    @Test func totalWidthForSingleCoverIsFrontSize() {
        let layout = CoverStackLayout(coverCount: 1, size: 100, peek: 20)
        #expect(layout.totalWidth == 100)
    }

    @Test func totalWidthAddsOnePeekPerExtraLayer() {
        let layout = CoverStackLayout(coverCount: 4, size: 76, peek: 17)
        #expect(layout.totalWidth == CGFloat(76 + 3 * 17))
    }

    @Test func frontCoverDrawsFlushLeft() {
        let layout = CoverStackLayout(coverCount: 3, size: 100, peek: 20)
        #expect(layout.xOffset(at: 0) == 0)
    }

    @Test func deeperLayersStepRightByPeek() {
        let layout = CoverStackLayout(coverCount: 3, size: 100, peek: 20)
        #expect(layout.xOffset(at: 1) == 20)
        #expect(layout.xOffset(at: 2) == 40)
    }

    @Test func scaleDecreasesMonotonicallyWithDepth() {
        let layout = CoverStackLayout(coverCount: 4, size: 100, peek: 20)
        #expect(layout.scale(at: 0) == 1.0)
        #expect(layout.scale(at: 1) < layout.scale(at: 0))
        #expect(layout.scale(at: 3) < layout.scale(at: 2))
    }

    @Test func frontCoverHasHighestZIndex() {
        let layout = CoverStackLayout(coverCount: 3, size: 100, peek: 20)
        #expect(layout.zIndex(at: 0) > layout.zIndex(at: 2))
    }
}

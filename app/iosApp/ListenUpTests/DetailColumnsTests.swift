import CoreGraphics
import Testing
@testable import ListenUp

/// The detail screens split by the width they actually have, at every point on the iPad continuum.
@Suite("Detail columns")
struct DetailColumnsTests {
    @Test func phonesStack() {
        #expect(DetailColumns.forWidth(390) == .stacked)
        #expect(DetailColumns.forWidth(440) == .stacked)
    }

    @Test func narrowSplitViewsStack() {
        // iPad Pro 13" landscape at 1/3 and 1/2 — regular or compact, too narrow for two columns.
        #expect(DetailColumns.forWidth(375) == .stacked)
        #expect(DetailColumns.forWidth(683) == .stacked)
    }

    @Test func fullWidthIPadsSplitWithAProportionalRail() {
        // iPad mini portrait, iPad Pro 11" portrait, iPad Pro 13" portrait and landscape.
        #expect(DetailColumns.forWidth(744) == .split(railWidth: 280))
        #expect(DetailColumns.forWidth(834) == .split(railWidth: 284))
        #expect(DetailColumns.forWidth(1032) == .split(railWidth: 351))
        #expect(DetailColumns.forWidth(1376) == .split(railWidth: 380))
    }

    @Test func theMainColumnNeverDropsBelowItsMinimum() {
        for width in stride(from: CGFloat(300), through: 1600, by: 7) {
            guard case .split(let rail) = DetailColumns.forWidth(width) else { continue }
            let main = width - rail - DetailColumns.gutter - 2 * DetailColumns.margin
            #expect(main >= DetailColumns.minimumMainWidth - 1, "main column \(main) at width \(width)")
            #expect(DetailColumns.railRange.contains(rail))
        }
    }

    @Test func theRailTakesAShareOfTheWidthWithinItsBand() {
        #expect(DetailColumns.railWidth(forWidth: 500) == 280)
        #expect(DetailColumns.railWidth(forWidth: 1000) == 340)
        #expect(DetailColumns.railWidth(forWidth: 2000) == 380)
    }

    @Test func theFirstFrameGuessFollowsTheSizeClass() {
        #expect(DetailColumns.estimate(horizontalSizeClass: .compact) == .stacked)
        #expect(DetailColumns.estimate(horizontalSizeClass: nil) == .stacked)
        #expect(DetailColumns.estimate(horizontalSizeClass: .regular) != .stacked)
    }
}

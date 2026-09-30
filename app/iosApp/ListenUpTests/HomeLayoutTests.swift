import CoreGraphics
import Testing
@testable import ListenUp

/// Home runs the full width it is given, and only puts stats beside shelves when there is room.
@Suite("Home layout")
struct HomeLayoutTests {
    @Test func phonesAndNarrowSplitViewsAreOneColumn() {
        for width: CGFloat in [375, 390, 440, 683, 834] {
            #expect(HomeLayout.forWidth(width).arrangement == .column, "width \(width)")
        }
    }

    @Test func wideWindowsPutStatsBesideShelves() {
        #expect(HomeLayout.forWidth(1032).arrangement == .statsBesideShelves(statsWidth: 351))
        #expect(HomeLayout.forWidth(1376).arrangement == .statsBesideShelves(statsWidth: 420))
    }

    @Test func continueCardsGrowWithTheWidthWithinABand() {
        #expect(HomeLayout.forWidth(390).continueCardWidth == 140)
        #expect(HomeLayout.forWidth(1032).continueCardWidth == 172)
        #expect(HomeLayout.forWidth(1376).continueCardWidth == 200)
    }

    @Test func marginsWidenOnceThereIsRoom() {
        #expect(HomeLayout.forWidth(390).margin == 20)
        #expect(HomeLayout.forWidth(1032).margin == 32)
    }
}

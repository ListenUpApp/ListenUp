import CoreGraphics
import Testing
@testable import ListenUp

/// The full player picks its arrangement from the space it actually has (HIG, Layout: "Design a
/// layout that adapts gracefully … respecting system-defined safe areas") — never from the
/// device or the horizontal size class alone. Sizes below are safe-area sizes.
@Suite("PlayerLayoutMode")
struct PlayerLayoutTests {
    // MARK: - Mode

    @Test func iPhonePortraitStacks() {
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 402, height: 790), isAccessibilitySize: false) == .stacked)
    }

    @Test func smallIPhonePortraitStacks() {
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 375, height: 600), isAccessibilitySize: false) == .stacked)
    }

    /// Pro Max landscape reports a REGULAR width, which used to buy it the iPad two-pane layout in
    /// ~420pt of height. Short-and-wide puts the cover beside the controls instead.
    @Test func phoneLandscapePutsTheCoverBesideTheControls() {
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 832, height: 419), isAccessibilitySize: false) == .compactHeight)
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 832, height: 419), isAccessibilitySize: true) == .compactHeight)
    }

    @Test func iPadFullScreenShowsTheChapterPane() {
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 1376, height: 990), isAccessibilitySize: false) == .regular)
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 834, height: 1150), isAccessibilitySize: false) == .regular)
    }

    /// A third-width Split View is phone-narrow, however tall the iPad is.
    @Test func narrowSplitViewStacks() {
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 375, height: 990), isAccessibilitySize: false) == .stacked)
    }

    /// At accessibility text sizes the pane crowds the column, so it needs more width to earn it.
    @Test func accessibilityTextNeedsMoreWidthForThePane() {
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 834, height: 1150), isAccessibilitySize: true) == .stacked)
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 1376, height: 990), isAccessibilitySize: true) == .regular)
    }

    // MARK: - Cover

    @Test func stackedCoverFillsTheWidthLessMargins() {
        let side = PlayerLayoutMode.coverSide(in: CGSize(width: 402, height: 1000), mode: .stacked)
        #expect(side == 402 - 2 * PlayerLayoutMode.horizontalMargin)
    }

    @Test func stackedCoverGivesWayToAShortWindow() {
        let side = PlayerLayoutMode.coverSide(in: CGSize(width: 402, height: 600), mode: .stacked)
        #expect(side == 600 * PlayerLayoutMode.stackedCoverHeightShare)
    }

    @Test func compactHeightCoverFitsTheHeight() {
        let side = PlayerLayoutMode.coverSide(in: CGSize(width: 832, height: 419), mode: .compactHeight)
        #expect(side <= 419 - 2 * PlayerLayoutMode.verticalMargin)
        #expect(side <= 832 * 0.4)
    }

    @Test func coverNeverShrinksBelowTheFloorOrGrowsPastTheCeiling() {
        #expect(PlayerLayoutMode.coverSide(in: CGSize(width: 120, height: 150), mode: .stacked) == PlayerLayoutMode.coverRange.lowerBound)
        #expect(PlayerLayoutMode.coverSide(in: CGSize(width: 2000, height: 2000), mode: .stacked) == PlayerLayoutMode.coverRange.upperBound)
    }
}

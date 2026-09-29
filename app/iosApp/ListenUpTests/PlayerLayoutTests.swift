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

    @Test func stackedCoverFillsTheWidthWhenThereIsRoom() {
        let side = PlayerLayoutMode.stackedCoverSide(columnWidth: 402, availableHeight: 1000, controlsHeight: 400)
        #expect(side == 402 - 2 * PlayerLayoutMode.horizontalMargin)
    }

    /// A short window shrinks the cover to what the controls leave over, instead of scrolling.
    @Test func stackedCoverGivesWayToTheControls() {
        let side = PlayerLayoutMode.stackedCoverSide(columnWidth: 402, availableHeight: 700, controlsHeight: 400)
        #expect(side == 700 - 400 - PlayerLayoutMode.stackedCoverSpacing)
    }

    /// Below the floor the cover would be a thumbnail — the column scrolls instead.
    @Test func stackedColumnScrollsWhenEvenTheSmallestCoverCannotFit() {
        #expect(PlayerLayoutMode.stackedCoverSide(columnWidth: 402, availableHeight: 500, controlsHeight: 400) == nil)
    }

    @Test func stackedCoverStopsAtTheCeiling() {
        let side = PlayerLayoutMode.stackedCoverSide(columnWidth: 2000, availableHeight: 2000, controlsHeight: 300)
        #expect(side == PlayerLayoutMode.coverRange.upperBound)
    }

    @Test func compactHeightCoverFitsTheHeightAndLeavesTheControlsRoom() {
        let side = PlayerLayoutMode.compactHeightCoverSide(in: CGSize(width: 1000, height: 375))
        #expect(side == 375 - 2 * PlayerLayoutMode.verticalMargin)
        #expect(PlayerLayoutMode.compactHeightCoverSide(in: CGSize(width: 600, height: 375)) == 600 * 0.4)
    }

    @Test func scrollingCoverNarrowsToTheColumnButNotBelowTheFloor() {
        #expect(PlayerLayoutMode.scrollingCoverSide(columnWidth: 402) == PlayerLayoutMode.preferredScrollingCoverSide)
        #expect(PlayerLayoutMode.scrollingCoverSide(columnWidth: 200) == 200 - 2 * PlayerLayoutMode.horizontalMargin)
        #expect(PlayerLayoutMode.scrollingCoverSide(columnWidth: 150) == PlayerLayoutMode.coverRange.lowerBound)
    }
}

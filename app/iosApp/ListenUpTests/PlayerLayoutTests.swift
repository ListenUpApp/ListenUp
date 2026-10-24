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
        let landscape = CGSize(width: 832, height: 419)
        #expect(PlayerLayoutMode.resolve(size: landscape, isAccessibilitySize: false) == .compactHeight)
        #expect(PlayerLayoutMode.resolve(size: landscape, isAccessibilitySize: true) == .compactHeight)
    }

    @Test func iPadFullScreenShowsTheChapterPane() {
        let fullScreen = CGSize(width: 1376, height: 990)
        let portrait = CGSize(width: 834, height: 1150)
        #expect(PlayerLayoutMode.resolve(size: fullScreen, isAccessibilitySize: false) == .regular)
        #expect(PlayerLayoutMode.resolve(size: portrait, isAccessibilitySize: false) == .regular)
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

    // MARK: - Multitasking (HIG, Multitasking: an iPad app can appear at any window size)

    @Test func halfSplitViewOn13InchStacksWithTheChapterSheet() {
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 683, height: 1000), isAccessibilitySize: false)
            == .stacked)
    }

    @Test func twoThirdsSplitViewOn13InchShowsThePanel() {
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 900, height: 1000), isAccessibilitySize: false)
            == .regular)
    }

    @Test func stageManagerSmallWindowStacks() {
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 700, height: 560), isAccessibilitySize: false) == .stacked)
    }

    @Test func stageManagerShortWideWindowPutsTheCoverBeside() {
        #expect(PlayerLayoutMode.resolve(size: CGSize(width: 1100, height: 480), isAccessibilitySize: false)
            == .compactHeight)
    }

    // MARK: - Inside .regular (the NP-iPad board: the cover beside the controls)

    @Test func aWidePlayerAreaPutsTheCoverBesideTheControls() {
        #expect(PlayerLayoutMode.regularArrangement(playerWidth: 1180 - PlayerLayoutMode.panelWidth)
            == .coverBeside(coverSide: 330))
    }

    @Test func anUprightIPadStacksTheCoverOverTheControls() {
        #expect(PlayerLayoutMode.regularArrangement(playerWidth: 834 - PlayerLayoutMode.panelWidth) == .stacked)
    }

    @Test func theCoverGoesBesideOnlyWhenItCanBeAtLeast240() {
        let threshold = PlayerLayoutMode.coverBesideMinimumPlayerWidth
        #expect(PlayerLayoutMode.regularArrangement(playerWidth: threshold) == .coverBeside(coverSide: 240))
        #expect(PlayerLayoutMode.regularArrangement(playerWidth: threshold - 1) == .stacked)
    }

    /// At accessibility text sizes the controls need the whole column, so the cover goes back above them and the column
    /// scrolls (HIG, Typography: "Consider adjusting your layout at large font sizes").
    @Test func accessibilityTextStacksTheRegularColumn() {
        #expect(PlayerLayoutMode.regularArrangement(
            playerWidth: 1180 - PlayerLayoutMode.panelWidth, isAccessibilitySize: true) == .stacked)
    }

    // MARK: - Fold (mirrors Android's NowPlayingLayoutTest)

    private static let unfolded = CGSize(width: 673, height: 841)
    private static let horizontalHinge = CGRect(x: 0, y: 420, width: 673, height: 0)
    private static let verticalHinge = CGRect(x: 336, y: 0, width: 0, height: 841)

    @Test func noFoldLeavesTheChoiceToTheWindow() {
        #expect(PlayerLayoutMode.resolve(size: Self.unfolded, isAccessibilitySize: false, fold: .none) == .stacked)
        #expect(PlayerLayoutMode.resolve(
            size: CGSize(width: 1376, height: 990), isAccessibilitySize: false, fold: .none) == .regular)
        #expect(PlayerLayoutMode.resolve(
            size: CGSize(width: 832, height: 419), isAccessibilitySize: false, fold: .none) == .compactHeight)
    }

    @Test func halfOpenWithAHorizontalHingeSplitsThePlayerAcrossTheFold() {
        let mode = PlayerLayoutMode.resolve(
            size: Self.unfolded, isAccessibilitySize: false, fold: .tabletop(hinge: Self.horizontalHinge))
        #expect(mode == .tabletop(hinge: Self.horizontalHinge))
    }

    @Test func tabletopWinsOverTheWindowEvenOneWideEnoughForThePane() {
        let wide = CGSize(width: 1376, height: 990)
        let hinge = CGRect(x: 0, y: 495, width: 1376, height: 0)
        #expect(PlayerLayoutMode.resolve(size: wide, isAccessibilitySize: false, fold: .tabletop(hinge: hinge))
            == .tabletop(hinge: hinge))
    }

    @Test func halfOpenLikeABookPutsTheCoverAndTheChaptersOnFacingPages() {
        // Below the pane's 768pt minimum, which alone would stack.
        let mode = PlayerLayoutMode.resolve(
            size: Self.unfolded, isAccessibilitySize: false, fold: .book(hinge: Self.verticalHinge))
        #expect(mode == .book(hinge: Self.verticalHinge))
    }

    @Test func accessibilitySizesDoNotChangeTheFoldsChoice() {
        #expect(PlayerLayoutMode.resolve(
            size: Self.unfolded, isAccessibilitySize: true, fold: .tabletop(hinge: Self.horizontalHinge))
            == .tabletop(hinge: Self.horizontalHinge))
        #expect(PlayerLayoutMode.resolve(
            size: Self.unfolded, isAccessibilitySize: true, fold: .book(hinge: Self.verticalHinge))
            == .book(hinge: Self.verticalHinge))
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

    /// At accessibility sizes the column never fits its cover to a measured height: it scrolls, so the settings row
    /// is always reachable (an iPad at AX2 clipped it).
    @Test func accessibilitySizesAlwaysScroll() {
        #expect(PlayerLayoutMode.stackedCoverSide(
            columnWidth: 620, availableHeight: 776, controlsHeight: 300, isAccessibilitySize: true) == nil)
        #expect(PlayerLayoutMode.stackedCoverSide(
            columnWidth: 620, availableHeight: 776, controlsHeight: 300, isAccessibilitySize: false) != nil)
    }
}

/// The window's fold, read from the active division regions (`GeometryProxy.reservedRegions(kind: .division)`),
/// which only carry the fold while the phone is partly folded.
@Suite("PlayerFold")
struct PlayerFoldTests {
    @Test func noDivisionIsNoFold() {
        #expect(PlayerFold.from(divisionFrames: []) == .none)
    }

    @Test func aHingeAcrossTheScreenIsTabletop() {
        let hinge = CGRect(x: 0, y: 400, width: 700, height: 20)
        #expect(PlayerFold.from(divisionFrames: [hinge]) == .tabletop(hinge: hinge))
    }

    @Test func aHingeDownTheScreenIsABook() {
        let hinge = CGRect(x: 340, y: 0, width: 20, height: 840)
        #expect(PlayerFold.from(divisionFrames: [hinge]) == .book(hinge: hinge))
    }

    /// A seamless fold reports no thickness: it is still a fold, a line across the screen.
    @Test func aZeroThicknessHingeIsStillAFold() {
        let line = CGRect(x: 0, y: 420, width: 700, height: 0)
        #expect(PlayerFold.from(divisionFrames: [line]) == .tabletop(hinge: line))
    }

    @Test func theFirstDivisionDecides() {
        let across = CGRect(x: 0, y: 400, width: 700, height: 0)
        let down = CGRect(x: 340, y: 0, width: 0, height: 840)
        #expect(PlayerFold.from(divisionFrames: [across, down]) == .tabletop(hinge: across))
    }
}

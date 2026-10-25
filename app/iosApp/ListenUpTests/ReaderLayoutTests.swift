import CoreGraphics
import SwiftUI
import Testing
@testable import ListenUp

/// The reader puts the player beside the page when the screen has room. One spec with Android's `readerLayout`
/// (`features/documentviewer/ReaderLayoutTest.kt`): the cases mirror each other name for name.
@Suite("ReaderLayoutMode")
struct ReaderLayoutTests {
    private static let verticalHinge = CGRect(x: 336, y: 0, width: 0, height: 841)
    private static let horizontalHinge = CGRect(x: 0, y: 420, width: 673, height: 0)

    private static func resolve(
        _ horizontal: UserInterfaceSizeClass,
        _ vertical: UserInterfaceSizeClass,
        fold: PlayerFold = .none,
        hasCurrentBook: Bool = true
    ) -> ReaderLayoutMode {
        ReaderLayoutMode.resolve(
            horizontalSizeClass: horizontal, verticalSizeClass: vertical, fold: fold, hasCurrentBook: hasCurrentBook)
    }

    @Test func noBookInThePlayerKeepsTheSinglePage() {
        #expect(Self.resolve(.regular, .regular, hasCurrentBook: false) == .single)
        #expect(Self.resolve(.compact, .regular, fold: .book(hinge: Self.verticalHinge), hasCurrentBook: false)
            == .single)
    }

    @Test func halfOpenLikeABookPutsThePageAndThePlayerOnFacingPages() {
        #expect(Self.resolve(.regular, .regular, fold: .book(hinge: Self.verticalHinge))
            == .beside(.hinge(Self.verticalHinge)))
    }

    @Test func halfOpenOnATableKeepsTheSinglePageAndItsStrip() {
        #expect(Self.resolve(.regular, .regular, fold: .tabletop(hinge: Self.horizontalHinge)) == .single)
    }

    /// iPad full screen, either way up.
    @Test func aWideAndTallWindowPutsThePlayerBesideThePage() {
        #expect(Self.resolve(.regular, .regular) == .beside(.trailingPane))
    }

    /// An iPhone upright, or an iPad in a narrow Split View.
    @Test func aNarrowWindowKeepsTheSinglePage() {
        #expect(Self.resolve(.compact, .regular) == .single)
        #expect(Self.resolve(.compact, .compact) == .single)
    }

    /// A Pro Max on its side reports a regular width with a compact height.
    @Test func aWideButShortWindowKeepsTheSinglePage() {
        #expect(Self.resolve(.regular, .compact) == .single)
    }

    @Test func aBookFoldWinsOverANarrowWindow() {
        #expect(Self.resolve(.compact, .regular, fold: .book(hinge: Self.verticalHinge))
            == .beside(.hinge(Self.verticalHinge)))
    }

    @Test func tabletopWinsOverAWideWindow() {
        #expect(Self.resolve(.regular, .regular, fold: .tabletop(hinge: Self.horizontalHinge)) == .single)
    }

    /// A flat iPhone Duo has no active division: it is decided by its size classes alone.
    @Test func aFlatFoldIsJustAWindow() {
        #expect(Self.resolve(.compact, .regular, fold: .none) == .single)
        #expect(Self.resolve(.regular, .regular, fold: .none) == .beside(.trailingPane))
    }
}

/// How wide the page, the gap over the hinge, and the listening pane are beside each other.
@Suite("ReaderColumns")
struct ReaderColumnsTests {
    @Test func aHingeSplitsThePagesWithClearanceEitherSide() {
        let hinge = CGRect(x: 330, y: 0, width: 12, height: 841)
        let columns = ReaderColumns(split: .hinge(hinge), width: 673, layoutDirection: .leftToRight)
        #expect(columns == ReaderColumns(page: 330 - 16, gap: 12 + 32, pane: 673 - 342 - 16))
    }

    @Test func aSeamlessHingeStillKeepsClearOfTheCrease() {
        let line = CGRect(x: 336, y: 0, width: 0, height: 841)
        let columns = ReaderColumns(split: .hinge(line), width: 672, layoutDirection: .leftToRight)
        #expect(columns == ReaderColumns(page: 320, gap: 32, pane: 320))
    }

    /// Right to left, the leading page is the right-hand one: the page and the pane trade sides of the hinge.
    @Test func rightToLeftPutsThePageOnTheRightOfTheHinge() {
        let hinge = CGRect(x: 300, y: 0, width: 0, height: 841)
        let columns = ReaderColumns(split: .hinge(hinge), width: 700, layoutDirection: .rightToLeft)
        #expect(columns == ReaderColumns(page: 700 - 300 - 16, gap: 32, pane: 300 - 16))
    }

    @Test func aWideWindowGivesThePaneItsFixedWidth() {
        let columns = ReaderColumns(split: .trailingPane, width: 1376, layoutDirection: .leftToRight)
        let pane = ReaderLayoutMode.paneWidth
        #expect(columns == ReaderColumns(page: 1376 - pane, gap: 0, pane: pane))
    }
}

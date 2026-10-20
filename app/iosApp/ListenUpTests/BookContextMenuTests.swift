import Foundation
import ListenupContract
import Testing
@preconcurrency import Shared
@testable import ListenUp

/// A book's context menu: what it offers for what the screen can do, and the share link it hands out.
@MainActor
@Suite("Book context menu")
struct BookContextMenuTests {
    @Test func memberMenuIsTheBoardsFourInThreeGroups() {
        #expect(BookContextMenuModel.sections(canShare: true, canSelect: true, isAdmin: false)
            == [[.primary], [.addToShelf, .share], [.select]])
    }

    /// Open question 2: the admin group on every platform, last and short.
    @Test func adminMenuAddsTheAdminGroupLast() {
        #expect(BookContextMenuModel.sections(canShare: true, canSelect: true, isAdmin: true).last
            == [.addToCollection, .editDetails])
    }

    /// The collection picker and the editor ride the screen's selection; without one there is no admin group.
    @Test func withoutASelectionAnAdminGetsNoAdminGroup() {
        #expect(BookContextMenuModel.sections(canShare: true, canSelect: false, isAdmin: true)
            == [[.primary], [.share]])
    }

    @Test func primaryLabelFollowsTheBook() {
        #expect(BookMenuPrimaryValue.resume(timeLeftMs: 21_720_000).label == "Resume · 6h 2m")
        #expect(BookMenuPrimaryValue.play.label == "Play")
        #expect(BookMenuPrimaryValue.listenAgain.label == "Listen Again")
    }

    /// The shared `bookMenuPrimary` rule, read from the card's own state.
    @Test func primaryFollowsTheCardState() {
        #expect(BookMenuPrimaryValue(.inProgress(fraction: 0.1, timeLeftMs: 60_000)) == .resume(timeLeftMs: 60_000))
        #expect(BookMenuPrimaryValue(.finished(durationMs: 1)) == .listenAgain)
        #expect(BookMenuPrimaryValue(.notStarted(durationMs: 1)) == .play)
        #expect(BookMenuPrimaryValue(nil) == .play)
    }

    /// Hide, don't dim (HIG, Context menus): a screen without a selection has no shelf picker and no
    /// selection mode to enter, so both go.
    @Test func withoutASelectionTheMenuIsPlayAndShare() {
        #expect(BookContextMenuModel.sections(canShare: true, canSelect: false) == [[.primary], [.share]])
    }

    /// Offline with no server identity cached, there is no link to share.
    @Test func withoutAShareLinkShareIsLeftOut() {
        #expect(BookContextMenuModel.sections(canShare: false, canSelect: true)
            == [[.primary], [.addToShelf], [.select]])
        #expect(BookContextMenuModel.sections(canShare: false, canSelect: false) == [[.primary]])
    }

    /// The link decodes back to the same book on the same server, through the shared codec.
    @Test func theShareLinkNamesTheBookAndItsServer() throws {
        let url = try #require(BookShareLink.url(bookId: "b1", instanceId: "inst-1", remoteUrl: "https://lib.example.com/"))
        let target = try #require(ShareLinkCodec.shared.decode(raw: url.absoluteString))
        guard case .book(let bookType) = target.sealedType() else {
            Issue.record("decoded \(target) is not a book")
            return
        }
        #expect(bookType.value.bookId.value == "b1")
        #expect(bookType.value.serverInstanceId == "inst-1")
        #expect(bookType.value.serverUrl == "https://lib.example.com")
    }
}

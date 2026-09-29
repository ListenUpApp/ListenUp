import Foundation
import Testing
@preconcurrency import Shared
@testable import ListenUp

/// A book's context menu: what it offers for what the screen can do, and the share link it hands out.
@MainActor
@Suite("Book context menu")
struct BookContextMenuTests {
    @Test func aSelectableSharableBookOffersEverythingInOrder() {
        #expect(BookContextMenuModel.sections(canShare: true, canSelect: true)
            == [[.play], [.addToShelf, .share], [.select]])
    }

    /// Hide, don't dim (HIG, Context menus): a screen without a selection has no shelf picker and no
    /// selection mode to enter, so both go.
    @Test func withoutASelectionTheMenuIsPlayAndShare() {
        #expect(BookContextMenuModel.sections(canShare: true, canSelect: false) == [[.play], [.share]])
    }

    /// Offline with no server identity cached, there is no link to share.
    @Test func withoutAShareLinkShareIsLeftOut() {
        #expect(BookContextMenuModel.sections(canShare: false, canSelect: true)
            == [[.play], [.addToShelf], [.select]])
        #expect(BookContextMenuModel.sections(canShare: false, canSelect: false) == [[.play]])
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

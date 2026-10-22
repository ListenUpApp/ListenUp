import Foundation
import Testing
@testable import ListenUp
@preconcurrency import Shared

@Suite("The shared More menu as native sections")
@MainActor
struct PlayerMenuModelTests {
    @Test("a book in a series with a PDF and a boost: the rows in order, Close Book destructive and alone")
    func rows() {
        let snapshot = PlayerSnapshot.from(PlayerUiStateLoaded.fixture(firstPdfDocId: "d1", boostDb: 6))
        #expect(snapshot.menu.map(\.isDestructive) == [false, true])
        #expect(snapshot.menu.first?.rows == [.goToBook, .goToSeries("series-1"), .openPdf, .volumeBoost(6)])
        #expect(snapshot.menu.last?.rows == [.closeBook])
    }

    @Test("no Go to Author or Go to Narrator: the byline covers them")
    func noContributorRows() {
        let titles = PlayerSnapshot.from(PlayerUiStateLoaded.fixture()).menu.flatMap(\.rows).map(\.title)
        #expect(!titles.contains(String(localized: "player.go_to_author")))
        #expect(!titles.contains(String(localized: "player.go_to_narrator")))
    }

    @Test("the boost row reads its value")
    func boostTitle() {
        let off = String(localized: "player.boost_off")
        #expect(PlayerMenuModel.Row.volumeBoost(0).title
            == String(format: String(localized: "player.np_volume_boost_row_ios"), off))
    }
}

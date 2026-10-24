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

    /// On iPad's wide player the series is a link above the title, so More leaves Go to Series out (P10); the phone
    /// keeps it. The shared `PlayerMenus.moreMenu(includesSeries:)` decides.
    @Test("Go to Series is in More only where the series isn't already a link")
    func includesSeries() {
        let state = PlayerUiStateLoaded.fixture()
        let with = PlayerMenuModel.sections(for: state, includesSeries: true).flatMap(\.rows)
        let without = PlayerMenuModel.sections(for: state, includesSeries: false).flatMap(\.rows)
        #expect(with.contains(.goToSeries("series-1")))
        #expect(!without.contains { if case .goToSeries = $0 { true } else { false } })
        #expect(without.contains(.goToBook))
        #expect(PlayerSnapshot.from(state).menuWithoutSeries.flatMap(\.rows) == without)
    }

    @Test("the boost row reads its value")
    func boostTitle() {
        let off = String(localized: "player.boost_off")
        #expect(PlayerMenuModel.Row.volumeBoost(0).title
            == String(format: String(localized: "player.np_volume_boost_row_ios"), off))
    }
}

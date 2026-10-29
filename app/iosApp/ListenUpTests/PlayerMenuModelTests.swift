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
        #expect(snapshot.menu.first?.rows
            == [.goToBook, .goToSeries("series-1"), .goToAuthor("a1"), .openPdf, .volumeBoost(6)])
        #expect(snapshot.menu.last?.rows == [.closeBook])
    }

    @Test("one author: Go to Author, right after Go to Series; never Go to Narrator")
    func oneAuthor() {
        let rows = PlayerSnapshot.from(PlayerUiStateLoaded.fixture()).menu.flatMap(\.rows)
        let series = rows.firstIndex(of: .goToSeries("series-1"))
        #expect(series != nil)
        #expect(rows.firstIndex(of: .goToAuthor("a1")) == series.map { $0 + 1 })
        #expect(PlayerMenuModel.Row.goToAuthor("a1").title == String(localized: "player.go_to_author_ios"))
        #expect(!rows.map(\.title).contains(String(localized: "player.go_to_narrator")))
    }

    @Test("several authors: Go to Author… chooses among them")
    func severalAuthors() {
        let state = PlayerUiStateLoaded.fixture(authors: [
            PlayerContributor(id: "a1", name: "Brandon Sanderson", bookCount: 14),
            PlayerContributor(id: "a2", name: "Dan Wells", bookCount: 6)
        ])
        let rows = PlayerSnapshot.from(state).menu.flatMap(\.rows)
        #expect(rows.contains(.chooseAuthor))
        #expect(PlayerMenuModel.Row.chooseAuthor.title == String(localized: "player.go_to_author_multiple_ios"))
    }

    @Test("iPad's wide player shows the authors on its byline, so More has no author row")
    func iPadHasNoAuthorRow() {
        let rows = PlayerSnapshot.from(PlayerUiStateLoaded.fixture()).menuWithoutSeries.flatMap(\.rows)
        #expect(!rows.contains { if case .goToAuthor = $0 { true } else { false } })
        #expect(!rows.contains(.chooseAuthor))
    }

    /// On iPad's wide player the series is a link above the title, so More leaves Go to Series out (P10); the phone
    /// keeps it. The shared `PlayerMenus.moreMenu(includesSeries:)` decides.
    @Test("Go to Series is in More only where the series isn't already a link")
    func includesSeries() {
        let state = PlayerUiStateLoaded.fixture()
        let with = PlayerMenuModel.sections(for: state, includesSeries: true, includesAuthor: true).flatMap(\.rows)
        let without = PlayerMenuModel.sections(for: state, includesSeries: false, includesAuthor: false).flatMap(\.rows)
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

import Foundation
@preconcurrency import Shared

/// One section of Now Playing's More menu, from the shared `PlayerMenus` (§5.5.7). Close Book's section is destructive.
struct PlayerMenuSection: Equatable {
    let rows: [PlayerMenuModel.Row]
    let isDestructive: Bool
}

/// The shared More menu as native rows (rule 8).
enum PlayerMenuModel {
    enum Row: Hashable {
        case goToBook
        case goToSeries(String)
        /// The book's only author.
        case goToAuthor(String)
        /// Several authors: a submenu of them (HIG, Menus: a submenu for a short list of related choices).
        case chooseAuthor
        case openPdf
        case volumeBoost(Float)
        case closeBook

        /// Nil only for Keyboard Shortcuts, which iOS never asks for: the iPad lists its keys in the system's
        /// ⌘-hold overlay, so `sections(for:)` passes `includesShortcuts: false`.
        init?(_ item: any PlayerMenuItem) {
            switch item.sealedType() {
            case .goToBook: self = .goToBook
            case .goToSeries(let series): self = .goToSeries(series.value.seriesId)
            case .goToAuthor(let author): self = .goToAuthor(author.value.authorId)
            case .chooseAuthor: self = .chooseAuthor
            case .openPdf: self = .openPdf
            case .volumeBoost(let boost): self = .volumeBoost(boost.value.boostDb)
            case .keyboardShortcuts: return nil
            case .closeBook: self = .closeBook
            }
        }

        /// The row's words, in the HIG's title case; the boost row reads its value ("Volume Boost · Off").
        var title: String {
            switch self {
            case .goToBook: String(localized: "player.np_go_to_book_ios")
            case .goToSeries: String(localized: "player.np_go_to_series_ios")
            case .goToAuthor: String(localized: "player.go_to_author_ios")
            case .chooseAuthor: String(localized: "player.go_to_author_multiple_ios")
            case .openPdf: String(localized: "player.open_pdf")
            case .volumeBoost(let db):
                String(format: String(localized: "player.np_volume_boost_row_ios"), BoostPickerSheet.formatBoost(db))
            case .closeBook: String(localized: "player.np_close_book_ios")
            }
        }

        var systemImage: String {
            switch self {
            case .goToBook: "book"
            case .goToSeries: "books.vertical"
            case .goToAuthor, .chooseAuthor: "person"
            case .openPdf: "doc.richtext"
            case .volumeBoost: "speaker.wave.3"
            case .closeBook: "xmark"
            }
        }
    }

    /// The More menu for `state`. Go to Series and Go to Author are in it unless the screen already shows them as links
    /// (`includesSeries`/`includesAuthor` false: iPad's wide player, the series above the title and the authors on the
    /// byline; P10).
    static func sections(for state: PlayerUiStateLoaded, includesSeries: Bool, includesAuthor: Bool) -> [PlayerMenuSection] {
        PlayerMenus.shared
            .moreMenu(state: state, includesSeries: includesSeries, includesAuthor: includesAuthor, includesShortcuts: false)
            .map { group in
                PlayerMenuSection(rows: group.items.compactMap(Row.init), isDestructive: group.isDestructive)
            }
    }
}

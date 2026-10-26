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
        case openPdf
        case volumeBoost(Float)
        case closeBook

        /// Nil only for Keyboard Shortcuts, which iOS never asks for: the iPad lists its keys in the system's
        /// ⌘-hold overlay, so `sections(for:)` passes `includesShortcuts: false`.
        init?(_ item: any PlayerMenuItem) {
            switch item.sealedType() {
            case .goToBook: self = .goToBook
            case .goToSeries(let series): self = .goToSeries(series.value.seriesId)
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
            case .openPdf: "doc.richtext"
            case .volumeBoost: "speaker.wave.3"
            case .closeBook: "xmark"
            }
        }
    }

    /// The More menu for `state`. Go to Series is in it unless the series is already a link on screen (`includesSeries`
    /// false: iPad's wide player, where the series sits above the title; P10).
    static func sections(for state: PlayerUiStateLoaded, includesSeries: Bool) -> [PlayerMenuSection] {
        PlayerMenus.shared
            .moreMenu(state: state, includesSeries: includesSeries, includesShortcuts: false)
            .map { group in
                PlayerMenuSection(rows: group.items.compactMap(Row.init), isDestructive: group.isDestructive)
            }
    }
}

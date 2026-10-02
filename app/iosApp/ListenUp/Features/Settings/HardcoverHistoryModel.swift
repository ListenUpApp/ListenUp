import Foundation
import Shared

/// The earlier-books offer (#1540), native, mapped once at the observer boundary (iosApp rule 8): the card,
/// the quiet row in Sync, the send in progress, or what it came to.
enum HardcoverHistoryModel: Equatable {
    case none
    case offer(books: Int)
    case available(books: Int)
    case sending(sent: Int, total: Int)
    case done(sent: Int, needsMatch: Int)

    /// Projects the shared state. Deliberately no `default`: a new state must fail to compile here.
    nonisolated static func from(_ history: HardcoverHistory) -> HardcoverHistoryModel {
        switch history.sealedType() {
        case .none:
            HardcoverHistoryModel.none
        case .offer(let offerType):
            .offer(books: Int(offerType.value.bookCount))
        case .available(let availableType):
            .available(books: Int(availableType.value.bookCount))
        case .sending(let sendingType):
            .sending(sent: Int(sendingType.value.sentBooks), total: Int(sendingType.value.totalBooks))
        case .done(let doneType):
            .done(sent: Int(doneType.value.sentBooks), needsMatch: Int(doneType.value.needsMatchBooks))
        }
    }
}

/// The earlier-books sentences, singular or plural, already resolved; buttons in title case (unit-tested).
enum HardcoverHistoryText {
    nonisolated static func offerBody(books: Int) -> String {
        books == 1
            ? String(localized: "hardcover.history_offer_body_one")
            : String(format: String(localized: "hardcover.history_offer_body"), books)
    }

    nonisolated static func sendTitle(books: Int) -> String {
        let sentence =
            books == 1
                ? String(localized: "hardcover.history_send_one")
                : String(format: String(localized: "hardcover.history_send"), books)
        return sentence.titleStyled
    }

    nonisolated static func notNowTitle() -> String {
        String(localized: "hardcover.history_not_now").titleStyled
    }

    nonisolated static func sendingTitle(sent: Int, total: Int) -> String {
        total == 1
            ? String(localized: "hardcover.history_sending_one")
            : String(format: String(localized: "hardcover.history_sending"), sent, total)
    }

    nonisolated static func progressValue(sent: Int, total: Int) -> String {
        String(format: String(localized: "hardcover.history_progress_value"), sent, total)
    }

    /// What the send came to. D4: when nothing could be sent, it says what waits for a match — never
    /// "Sent 0 books".
    nonisolated static func doneTitle(sent: Int, needsMatch: Int) -> String {
        if sent == 0 && needsMatch > 0 {
            return needsMatch == 1
                ? String(localized: "hardcover.history_none_sent_one")
                : String(format: String(localized: "hardcover.history_none_sent"), needsMatch)
        }
        if sent == 1 { return String(localized: "hardcover.history_sent_one") }
        if needsMatch == 0 && sent > 1 { return String(format: String(localized: "hardcover.history_sent_all"), sent) }
        return String(format: String(localized: "hardcover.history_sent"), sent)
    }

    nonisolated static func needsMatchLine(_ count: Int) -> String {
        count == 1
            ? String(localized: "hardcover.history_needs_match_one")
            : String(format: String(localized: "hardcover.history_needs_match"), count)
    }

    nonisolated static func rowTitle() -> String {
        String(localized: "hardcover.history_row_title").titleStyled
    }

    nonisolated static func availableDetail(books: Int) -> String {
        books == 1
            ? String(localized: "hardcover.history_row_detail_one")
            : String(format: String(localized: "hardcover.history_row_detail"), books)
    }
}

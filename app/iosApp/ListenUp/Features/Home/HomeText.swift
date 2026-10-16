import Foundation

/// What a Home card says (spec §2.5, §8.2).
enum HomeCardText {
    static func timeLeft(remainingMs: Int64) -> String { DurationFormatting.timeLeft(ms: remainingMs) }

    /// "Babel, by R. F. Kuang, 11 percent listened, 18 hr, 40 min left"; wide cards add "Read by …". The time left is
    /// one wording whatever its length (open question 6: no "Almost done").
    static func label(title: String, author: String, narrators: String?, percent: Int, remainingMs: Int64) -> String {
        [
            title,
            author.isEmpty ? nil : String(format: String(localized: "home.card_by_author"), author),
            narrators.flatMap { $0.isEmpty ? nil : String(format: String(localized: "library.card_read_by"), $0) },
            String(format: String(localized: "library.card_progress_a11y"), percent),
            timeLeft(remainingMs: remainingMs),
        ]
        .compactMap { $0 }
        .joined(separator: ", ")
    }
}

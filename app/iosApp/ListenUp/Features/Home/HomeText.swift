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

/// What the Resume hero says (spec §2.3).
enum ResumeHeroText {
    /// "Up Next · 2 hr. ago" (C14: the system's relative phrasing).
    static func eyebrow(lastPlayedAt: Date?, now: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        let when = lastPlayedAt.map { formatter.localizedString(for: $0, relativeTo: now) } ?? ""
        return String(format: String(localized: "home.up_next_ios"), when)
    }

    /// "Ch. 6 · Bridge Four", or nil for a book without chapters.
    static func chapter(number: Int?, title: String?) -> String? {
        guard let number, let title else { return nil }
        return String(format: String(localized: "book.detail_chapter_label"), number, title)
    }

    /// "11% of 45 hr, 30 min".
    static func progressOfLength(percent: Int, totalMs: Int64) -> String {
        String(
            format: String(localized: "home.progress_of_length"),
            String(format: String(localized: "common.percent"), percent),
            DurationFormatting.abbreviated(ms: totalMs)
        )
    }
}

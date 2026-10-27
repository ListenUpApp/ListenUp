import Foundation

/// What a Home card says (spec §2.5, §8.2).
enum HomeCardText {
    static func timeLeft(remainingMs: Int64) -> String { DurationFormatting.timeLeft(ms: remainingMs) }

    /// "Babel, by R. F. Kuang, 11 percent listened, 18 hr, 40 min left"; wide cards add "Read by …". The time left is
    /// one wording whatever its length (open question 6: no "Almost done").
    /// A card that needs a connection it does not have ends "not downloaded, needs a connection" instead (§2.5).
    static func label(
        title: String,
        author: String,
        narrators: String?,
        percent: Int,
        remainingMs: Int64,
        needsConnection: Bool = false
    ) -> String {
        [
            title,
            author.isEmpty ? nil : String(format: String(localized: "home.card_by_author"), author),
            narrators.flatMap { $0.isEmpty ? nil : String(format: String(localized: "library.card_read_by"), $0) },
            String(format: String(localized: "library.card_progress_a11y"), percent),
            needsConnection ? String(localized: "home.needs_connection_a11y") : timeLeft(remainingMs: remainingMs)
        ]
        .compactMap { $0 }
        .joined(separator: ", ")
    }

    /// The card's last line: the time left, or "Needs a connection" when it cannot play here.
    static func caption(remainingMs: Int64, needsConnection: Bool) -> String {
        needsConnection ? String(localized: "home.needs_connection") : timeLeft(remainingMs: remainingMs)
    }
}

/// What the just-finished card says (spec §2.3, C9, C10).
enum FinishedCardText {
    /// "Finished Today", "Finished Yesterday", "Finished 3 Days Ago" — title case throughout, as Home's "Up Next"
    /// eyebrow is — by calendar day in [calendar]'s time zone.
    static func eyebrow(finishedAt: Date, now: Date, calendar: Calendar = .current) -> String {
        let days = calendar.dateComponents(
            [.day],
            from: calendar.startOfDay(for: finishedAt),
            to: calendar.startOfDay(for: now)
        ).day ?? 0
        switch max(0, days) {
        case 0: return String(localized: "home.finished_today_ios")
        case 1: return String(localized: "home.finished_yesterday_ios")
        default: return String(format: String(localized: "home.finished_days_ago_ios"), days)
        }
    }

    /// "45 hr, 30 min · Sep 6 – Oct 10", or the length alone when the start is unknown (open question 8).
    static func meta(
        durationMs: Int64,
        startedAt: Date?,
        finishedAt: Date,
        locale: Locale = .current,
        calendar: Calendar = .current
    ) -> String {
        let length = DurationFormatting.abbreviated(ms: durationMs)
        guard let startedAt else { return length }
        var style = Date.FormatStyle.dateTime.month(.abbreviated).day()
        style.locale = locale
        style.calendar = calendar
        style.timeZone = calendar.timeZone
        let range = String(
            format: String(localized: "home.date_range"),
            startedAt.formatted(style),
            finishedAt.formatted(style)
        )
        return String(format: String(localized: "home.finished_line_no_range"), length, range)
    }

    /// "Maya, Tom and 3 more finished it too" (C9), or nil when nobody is named (open question 7).
    static func alsoFinished(names: [String], more: Int) -> String? {
        let named = names.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        switch (named.count, more) {
        case (0, _): return nil
        case (1, 0): return String(format: String(localized: "home.also_finished_one"), named[0])
        case (1, _): return String(format: String(localized: "home.also_finished_one_more"), named[0], more)
        case (_, 0): return String(format: String(localized: "home.also_finished_two"), named[0], named[1])
        default: return String(format: String(localized: "home.also_finished_two_more"), named[0], named[1], more)
        }
    }
}

/// What Nothing in Progress says (spec §2.3).
enum QuietSlotText {
    /// "Stormlight Archive, Book 2 · 48 hr, 15 min", or "Stormlight Archive · 48 hr, 15 min" without a number.
    static func seriesLine(series: String, sequence: String?, durationMs: Int64) -> String {
        let length = DurationFormatting.abbreviated(ms: durationMs)
        guard let sequence else { return String(format: String(localized: "home.series_length"), series, length) }
        return String(format: String(localized: "home.series_book_length"), series, sequence, length)
    }
}

/// What the first-sync strip says (spec §2.3 FirstSync, open question 1).
enum FirstSyncText {
    /// "Building your library", with " · 412 of 1,280 books" while a scan knows both numbers.
    static func title(count: ArrivalCount?) -> String {
        let building = String(localized: "library_setup.building_title")
        guard let count else { return building }
        let books = String(
            format: String(localized: "home.arriving_count"),
            count.done.formatted(),
            count.total.formatted()
        )
        return String(format: String(localized: "home.arriving_title"), building, books)
    }
}

/// What the stats card says (spec §2.6, C15).
enum HomeStatsText {
    static func streakChip(days: Int) -> String { String(format: String(localized: "home.streak_chip"), days) }

    static func bestStreak(days: Int) -> String {
        days == 1
            ? String(localized: "home.best_streak_one_day")
            : String(format: String(localized: "home.best_streak_days"), days)
    }

    /// "Listening per day, oldest to today: …, today 1 hour, 20 minutes".
    static func chartSummary(days: [DayBar]) -> String {
        let parts = days.sorted { $0.dayOffset > $1.dayOffset }.map { day in
            let amount = DurationFormatting.accessibleHoursMinutes(seconds: day.seconds)
            return day.dayOffset == 0 ? String(format: String(localized: "home.chart_today_a11y"), amount) : amount
        }
        return String(format: String(localized: "home.chart_a11y"), parts.joined(separator: ", "))
    }
}

/// "6 books · 74 hr" (spec §2.7).
enum HomeShelfText {
    static func meta(bookCount: Int, totalSeconds: Int) -> String {
        let length = totalSeconds > 0
            ? Duration.seconds(totalSeconds).formatted(
                .units(allowed: [.hours, .minutes], width: .abbreviated, maximumUnitCount: 1)
            )
            : nil
        switch (bookCount, length) {
        case (1, let length?): return String(format: String(localized: "home.shelf_one_book_duration"), length)
        case (1, nil): return String(localized: "home.shelf_one_book")
        case (_, let length?):
            return String(format: String(localized: "home.shelf_books_count_duration"), bookCount, length)
        case (_, nil): return String(format: String(localized: "home.shelf_books_count"), bookCount)
        }
    }
}

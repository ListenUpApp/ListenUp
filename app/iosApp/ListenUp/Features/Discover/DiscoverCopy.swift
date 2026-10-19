import Foundation

/// Discover's sentences that take numbers or dates, pure so they are tested off the view (spec §2.8).
/// Every string comes from the shared catalog, so iOS says what Android and web say.
enum DiscoverCopy {
    /// "2 listening now · 3 more in the last two weeks"; nil when nobody is shown.
    static func listeningCount(live: Int, recent: Int) -> String? {
        switch (live, recent) {
        case (0, 0): nil
        case (_, 0): String(format: String(localized: "discover.listening_count_live"), Int32(live))
        case (0, _): String(format: String(localized: "discover.listening_count_recent"), Int32(recent))
        default: String(format: String(localized: "discover.listening_count_both"), Int32(live), Int32(recent))
        }
    }

    /// "Book 1 of Mistborn", the bare series name when unnumbered, or "Standalone".
    static func seriesLine(series: String?, sequence: String?) -> String {
        guard let series, !series.isEmpty else { return String(localized: "discover.pick_standalone") }
        guard let sequence, !sequence.isEmpty else { return series }
        return String(format: String(localized: "discover.pick_series_line"), sequence, series)
    }

    /// A book's length in the platform's own abbreviated units ("24 hr, 39 min").
    static func length(ms: Int64) -> String {
        Duration.milliseconds(ms).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
    }

    /// Made per call, as `LastMatch` does: a formatter is not `Sendable`, so a shared static one is not
    /// concurrency-safe, and these run once per rendered row.
    private static var relative: RelativeDateTimeFormatter {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        formatter.dateTimeStyle = .named
        return formatter
    }

    /// "Added 2 days ago", never phrased in the future.
    static func added(_ date: Date, relativeTo now: Date) -> String {
        String(
            format: String(localized: "discover.recently_added_when"),
            relative.localizedString(for: min(date, now), relativeTo: now)
        )
    }

    /// "As of 2 hours ago": how old a roster is while the server cannot be reached; never in the future.
    static func asOf(_ date: Date, relativeTo now: Date) -> String {
        String(
            format: String(localized: "discover.listening_as_of"),
            relative.localizedString(for: min(date, now), relativeTo: now)
        )
    }

    private static var abbreviated: RelativeDateTimeFormatter {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter
    }

    /// How long ago, in the activity feed's abbreviated form ("2 hr. ago"); never in the future.
    static func lastActive(_ date: Date, relativeTo now: Date) -> String {
        abbreviated.localizedString(for: min(date, now), relativeTo: now)
    }

    /// A listener card's VoiceOver name: the book first, then who is on it and when.
    static func listenerLabel(book: String, name: String, isLive: Bool, when: String) -> String {
        isLive
            ? String(format: String(localized: "discover.listener_live_a11y"), book, name)
            : String(format: String(localized: "discover.listener_recent_a11y"), book, name, when)
    }
}

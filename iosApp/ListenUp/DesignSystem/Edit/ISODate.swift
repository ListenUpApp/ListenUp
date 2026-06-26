import Foundation

/// Parses/formats the `yyyy-MM-dd` ISO date strings the edit ViewModels store.
///
/// Uses the **device-local timezone** (`timeZone: .current`) on purpose: a date-only
/// `DatePicker` hands back **local-midnight** `Date`s, so parsing/formatting in the same
/// local frame round-trips the day stably in every timezone. Forcing UTC here would shift
/// an east-of-UTC user's picked day back by one. `ISO8601FormatStyle` parses strictly, so
/// malformed input throws (returned as `nil`).
enum ISODate {
    /// Date-only ISO-8601 (`yyyy-MM-dd`) in the device-local timezone. A value type — no
    /// shared mutable formatter, so no `nonisolated(unsafe)`.
    private static let style = Date.ISO8601FormatStyle(timeZone: .current)
        .year().month().day()

    static func parse(_ iso: String) -> Date? {
        iso.isEmpty ? nil : try? style.parse(iso)
    }

    static func format(_ date: Date) -> String {
        date.formatted(style)
    }
}

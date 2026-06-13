import Foundation

/// Parses/formats the `yyyy-MM-dd` ISO date strings the edit ViewModels store.
///
/// Uses the device-local timezone (the formatter's default) on purpose: a date-only
/// `DatePicker` hands back **local-midnight** `Date`s, so parsing/formatting in the same
/// local frame round-trips the day stably in every timezone. Forcing UTC here would shift
/// an east-of-UTC user's picked day back by one. Lenient-off rejects malformed input.
enum ISODate {
    private static let formatter: DateFormatter = {
        let fmt = DateFormatter()
        fmt.calendar = Calendar(identifier: .iso8601)
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd"
        fmt.isLenient = false
        return fmt
    }()

    static func parse(_ iso: String) -> Date? {
        iso.isEmpty ? nil : formatter.date(from: iso)
    }

    static func format(_ date: Date) -> String {
        formatter.string(from: date)
    }
}

import Foundation

/// Parses/formats the `yyyy-MM-dd` ISO date strings the edit ViewModels store. UTC,
/// lenient-off so malformed input is rejected.
enum ISODate {
    private static let formatter: DateFormatter = {
        let fmt = DateFormatter()
        fmt.calendar = Calendar(identifier: .iso8601)
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.timeZone = TimeZone(identifier: "UTC")
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

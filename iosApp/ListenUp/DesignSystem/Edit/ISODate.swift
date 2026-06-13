import Foundation

/// Parses/formats the `yyyy-MM-dd` ISO date strings the edit ViewModels store. UTC,
/// lenient-off so malformed input is rejected.
enum ISODate {
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .iso8601)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd"
        f.isLenient = false
        return f
    }()

    static func parse(_ iso: String) -> Date? {
        iso.isEmpty ? nil : formatter.date(from: iso)
    }

    static func format(_ date: Date) -> String {
        formatter.string(from: date)
    }
}

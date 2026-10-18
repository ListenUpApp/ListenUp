import Foundation

/// Home's navigation subtitle (spec §3.1): the part of the day the shared state chose, worded from the catalog, and
/// the listener's first name.
enum HomeTitle {
    nonisolated static func greeting(_ timeOfDay: HomeTimeOfDay) -> String {
        switch timeOfDay {
        case .morning: String(localized: "home.greeting_morning")
        case .afternoon: String(localized: "home.greeting_afternoon")
        case .evening: String(localized: "home.greeting_evening")
        case .night: String(localized: "home.greeting_night")
        }
    }

    /// "Good evening, Simon"; a new listener is greeted "Welcome, Simon" (spec §2.3 NewListener).
    nonisolated static func subtitle(timeOfDay: HomeTimeOfDay, firstName: String?, isWelcome: Bool = false) -> String {
        let greeting = isWelcome ? String(localized: "home.welcome") : greeting(timeOfDay)
        guard let firstName, !firstName.isEmpty else { return greeting }
        return String(format: String(localized: "home.greeting_with_name"), greeting, firstName)
    }
}

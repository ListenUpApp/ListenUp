import Foundation

/// Home's navigation subtitle: the time-of-day greeting and the listener's name, both pre-formatted
/// by the shared `HomeReady`.
enum HomeTitle {
    nonisolated static func subtitle(greeting: String, userName: String) -> String {
        switch (greeting.isEmpty, userName.isEmpty) {
        case (false, false): String(format: String(localized: "home.greeting_with_name"), greeting, userName)
        case (false, true): greeting
        case (true, false): userName
        case (true, true): ""
        }
    }
}

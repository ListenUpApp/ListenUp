import Foundation

/// Turns a stored language value into a human-readable, localized language name for display.
///
/// Books carry their language as a BCP-47-ish code (`"en"`, `"en-US"`, `"de"`); the UI should show
/// the real language ("English", "German"), localized to the user's locale. Values that aren't a
/// recognizable code (already a full name, or unknown) pass through unchanged, so this is safe to
/// apply blindly at the display boundary.
enum LanguageName {
    static func display(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return raw }
        // Use just the primary language subtag ("en" from "en-US") for the lookup.
        let code = trimmed.split(separator: "-").first.map(String.init) ?? trimmed
        return Locale.current.localizedString(forLanguageCode: code) ?? raw
    }
}

import SwiftUI

/// The app's few named non-brand colours. Raw colour literals live in `DesignSystem/` only (a
/// SwiftLint guard enforces it); screens name a token instead.
///
/// Each one is a designer's source colour run through the cover-tint clamp (`CoverTint`), which
/// keeps its hue and solves its shade per appearance to 4.5:1 as text on every system surface — so
/// a gold rank numeral or a licence chip reads in light, dark and Increase Contrast alike (HIG,
/// Color: "Make sure all your app's colors work well in light, dark, and increased contrast
/// contexts").
extension Color {
    /// Leaderboard podium: gold, silver, bronze.
    static let luPodiumGold = legible(red: 0.851, green: 0.604, blue: 0.071)
    static let luPodiumSilver = legible(red: 0.557, green: 0.584, blue: 0.639)
    static let luPodiumBronze = legible(red: 0.753, green: 0.478, blue: 0.220)

    /// Licence families on the Licences screen: Apache-2.0 blue (#2A6FDB), MIT green (#1F8A5B).
    static let luLicenseApache = legible(red: 0.165, green: 0.435, blue: 0.859)
    static let luLicenseMIT = legible(red: 0.122, green: 0.541, blue: 0.357)

    private static func legible(red: Double, green: Double, blue: Double) -> Color {
        CoverTint.clamp(red: red, green: green, blue: blue).color
    }
}

/// Parses a facet's accent-hue hex string (e.g. `"#2E5AA0"`, from the shared palette hash) into a
/// `Color`. Thin wrapper over `Color(hex:)` so callers read the domain intent at the call site; it
/// lives here because hex parsing is a design-system concern.
func hueColor(_ hex: String) -> Color {
    Color(hex: hex)
}

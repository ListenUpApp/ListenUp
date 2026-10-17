import SwiftUI

/// The app's few named non-brand colours. Raw colour literals live in `DesignSystem/` only (a
/// SwiftLint guard enforces it); screens name a token instead.
///
/// The podium fills are Color Sets, pinned with their ink by `LeaderRankColorTests`. The text colours
/// are a designer's source colour run through the cover-tint clamp (`CoverTint`), which keeps its hue
/// and solves its shade per appearance to 4.5:1 as text on every system surface — so a licence chip
/// reads in light, dark and Increase Contrast alike (HIG,
/// Color: "Make sure all your app's colors work well in light, dark, and increased contrast
/// contexts").
extension Color {
    /// Leaderboard podium discs: gold, silver and bronze fills — the `Podium*` Color Sets, the board's
    /// `#FFE08A` / `#E5E5EA` / `#F3C9A0` in light and a deeper shade of each in dark — always under
    /// `luPodiumInk` (`#241A00`), which reads at 4.5:1 on all three in every appearance.
    static let luPodiumGold = Color(ColorResource.podiumGold)
    static let luPodiumSilver = Color(ColorResource.podiumSilver)
    static let luPodiumBronze = Color(ColorResource.podiumBronze)
    static let luPodiumInk = Color(ColorResource.podiumInk)

    /// Licence families on the Licences screen: Apache-2.0 blue (#2A6FDB), MIT green (#1F8A5B).
    static let luLicenseApache = Color.legible(red: 0.165, green: 0.435, blue: 0.859)
    static let luLicenseMIT = Color.legible(red: 0.122, green: 0.541, blue: 0.357)

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

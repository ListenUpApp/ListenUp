import SwiftUI

/// The neutral Now Playing falls back to when its cover gives no colour (`CoverThemeObserver`): the player spec's warm
/// neutral (#F5EFE9, §3.9) in light mode, the system background in dark — so the state card, on the secondary
/// background, keeps an edge (HIG, Color: the background hierarchy layers content on secondary over primary).
enum PlayerWashTone {
    static func color(for scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(.systemBackground) : neutralLight
    }

    private static let neutralLight = Color(hex: "#F5EFE9")
}

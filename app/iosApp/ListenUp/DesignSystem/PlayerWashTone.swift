import SwiftUI

/// The neutral Now Playing falls back to when its cover gives no colour (`CoverThemeObserver`): the player spec's warm
/// neutral (#F5EFE9, §3.9) in light mode, the system's secondary background in dark (as Book Detail's hero does).
enum PlayerWashTone {
    static func color(for scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(.secondarySystemBackground) : neutralLight
    }

    private static let neutralLight = Color(hex: "#F5EFE9")
}

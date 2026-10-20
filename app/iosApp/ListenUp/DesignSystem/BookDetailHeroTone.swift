import SwiftUI

/// The neutral every hero falls back to when its cover gives no colour (`CoverThemeObserver`): the spec's neutral wash
/// (#F3EEEA) in light mode, the system's grouped secondary background in dark.
enum BookDetailHeroTone {
    static func color(for scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(.secondarySystemBackground) : neutralLight
    }

    private static let neutralLight = Color(hex: "#F3EEEA")
}

/// The navigation bar shows the book's title only once the hero title has scrolled under it (HIG, Toolbars): the
/// title is never on screen twice, and the bar still names the screen after you scroll past the hero.
enum BookDetailInlineTitle {
    static func isVisible(heroTitleMaxY: CGFloat) -> Bool { heroTitleMaxY < 0 }
}

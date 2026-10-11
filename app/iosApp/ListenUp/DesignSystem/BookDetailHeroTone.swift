import SwiftUI

/// The one colour Book Detail's hero sits on, until PR 3 derives it from the cover: the spec's neutral wash
/// (#F3EEEA) in light mode, the system's grouped secondary background in dark. Every hero surface reads it from here,
/// so the cover-derived wash replaces exactly this.
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

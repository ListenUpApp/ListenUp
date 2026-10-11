import SwiftUI

/// The one colour Book Detail's hero sits on, until PR 3 derives it from the cover (spec §2.2 neutral fallback).
/// Every hero surface reads it from here, so the cover-derived wash replaces exactly this.
enum BookDetailHeroTone {
    static func color(for scheme: ColorScheme) -> Color {
        scheme == .dark
            ? Color(.secondarySystemBackground)
            : Color(red: 0xF3 / 255, green: 0xEE / 255, blue: 0xEA / 255)
    }
}

/// The navigation bar shows the book's title only once the hero title has scrolled under it (HIG, Toolbars): the
/// title is never on screen twice, and the bar still names the screen after you scroll past the hero.
enum BookDetailInlineTitle {
    static func isVisible(heroTitleMaxY: CGFloat) -> Bool { heroTitleMaxY < 0 }
}

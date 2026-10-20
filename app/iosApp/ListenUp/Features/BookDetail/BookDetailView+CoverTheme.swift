import SwiftUI

@MainActor
extension BookDetailView {
    /// The band's colour: the cover's wash when it has one, else Book Detail's neutral hero tone.
    var heroWash: Color { Self.heroWash(coverTheme.colors, scheme: colorScheme) }

    /// What the cover colours load from: the book's id and cover hash, once the header is known.
    var coverKey: String? { observer?.header.map { "\($0.coverBookId)#\($0.coverHash ?? "")" } }

    static func heroWash(_ colors: CoverColors?, scheme: ColorScheme) -> Color {
        colors?.wash ?? BookDetailHeroTone.color(for: scheme)
    }
}

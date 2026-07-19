import SwiftUI
import Shared

// Maps a genre's auto-derived `FacetIcon` category to its SF Symbol, and a facet's accent hue
// (a hex string, from the shared `FacetIdentity.hue(name:)`/`.icon(name:)` derivation in
// `com.calypsan.listenup.client.presentation.genredestination`) to a native `Color` — the iOS
// half of the identity a clean-coral destination page renders as a tinted icon tile.
//
// Free functions, not a type: this file's name intentionally shadows the Kotlin `FacetIdentity`
// object (flat-typealiased onto `Shared` as `FacetIdentity.shared.hue(name:)`/`.icon(name:)`,
// reused directly for sub-genre chip dots — see `GenrePageModels.swift`). Keeping this file's own
// declarations as free functions avoids a same-name type collision between the two.

// swiftlint:disable cyclomatic_complexity
/// Resolves a [FacetIcon] category to the SF Symbol shown in a genre destination page's tinted
/// icon tile. Android binds the same platform-neutral `FacetIcon` enum to a Material glyph
/// (`FacetIdentityAndroid.kt`) — only the concrete icon *set* differs per platform. An exhaustive
/// switch over all 27 cases necessarily trips SwiftLint's complexity threshold; a lookup table
/// keyed by declaration order would pass the linter but silently mismatch if the Kotlin enum's
/// case order ever changes, so the exhaustive (and compiler-checked) switch is kept deliberately.
func sfSymbol(for icon: FacetIcon) -> String {
    switch icon {
    case .fantasy: "sparkles"
    case .scifi: "cube"
    case .mystery: "magnifyingglass"
    case .romance: "heart"
    case .horror: "flame"
    case .history: "building.columns"
    case .biography: "person.text.rectangle"
    case .business: "briefcase"
    case .science: "atom"
    case .selfHelp: "figure.mind.and.body"
    case .children: "figure.2.and.child.holdinghands"
    case .youngAdult: "person.3"
    case .health: "cross.case"
    case .food: "fork.knife"
    case .travel: "airplane"
    case .poetry: "text.quote"
    case .literary: "book.closed"
    case .religion: "hands.and.sparkles"
    case .art: "paintpalette"
    case .music: "music.note"
    case .comic: "text.bubble"
    case .anthology: "books.vertical"
    case .humor: "face.smiling"
    case .politics: "megaphone"
    case .tech: "cpu"
    case .sport: "figure.run"
    case .`default`: "book"
    }
}
// swiftlint:enable cyclomatic_complexity

/// Parses a facet's accent-hue hex string (e.g. `"#2E5AA0"`, from the shared palette hash) into a
/// `Color`. Thin wrapper over `Color(hex:)` so callers read the domain intent at the call site.
func hueColor(_ hex: String) -> Color {
    Color(hex: hex)
}

/// The tinted, rounded icon tile every clean-coral facet destination page (genre, tag, mood)
/// renders as its identity mark: the facet's accent hue at low-opacity fill + a faint hue border +
/// the mapped SF Symbol tinted the same hue. Never a full-bleed color-block hero. Shared by
/// `GenrePageView` and `FacetBooksView` so a genre, a tag, and a mood read as one visual family.
struct FacetIconTile: View {
    let symbolName: String
    let hue: Color
    var size: CGFloat = 60

    var body: some View {
        RoundedRectangle(cornerRadius: 17, style: .continuous)
            .fill(hue.opacity(0.16))
            .frame(width: size, height: size)
            .overlay {
                RoundedRectangle(cornerRadius: 17, style: .continuous)
                    .strokeBorder(hue.opacity(0.3), lineWidth: 1)
            }
            .overlay {
                Image(systemName: symbolName)
                    .font(.system(size: 26, weight: .semibold)) // decorative fixed size
                    .foregroundStyle(hue)
            }
            .accessibilityHidden(true)
    }
}

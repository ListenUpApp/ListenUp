import SwiftUI

/// The classification axis a chip group represents.
///
/// ListenUp describes a book on three distinct axes, and the chips read
/// differently so the reader can tell them apart at a glance:
///
/// - ``genre`` — *where the book lives* on the shelf (e.g. "Epic Fantasy").
///   Neutral fill, no icon: it's the structural home, not a flavour.
/// - ``tag`` — *the tropes inside it* (e.g. "Found Family"). A neutral *outlined*
///   chip — border, transparent fill, no icon — distinct from the genre's solid fill
///   and the mood's accent.
/// - ``mood`` — *how it feels* to read (e.g. "Tense", "Atmospheric"). A
///   coral-accented chip with a leading `sparkles` symbol — the most expressive
///   of the three.
///
/// The styling contract (icon vs. no-icon, accent vs. neutral, outlined vs. filled) is
/// exposed as pure properties so it can be pinned by unit tests without constructing SwiftUI views.
enum BookFacetKind: CaseIterable, Hashable {
    case genre
    case tag
    case mood

    /// Leading SF Symbol for the chip, or `nil` if the axis carries no icon.
    /// The symbol is decorative — the accessibility label carries the meaning.
    var symbolName: String? {
        switch self {
        case .genre: nil
        case .tag: nil
        case .mood: "sparkles"
        }
    }

    /// Whether the chip leans on the coral action accent. Only moods do now;
    /// tags are a neutral outlined facet.
    var usesAccent: Bool {
        switch self {
        case .genre, .tag: false
        case .mood: true
        }
    }

    /// Whether the chip is a transparent, border-stroked capsule (tags) vs a filled one.
    var isOutlined: Bool {
        switch self {
        case .tag: true
        case .genre, .mood: false
        }
    }

    /// VoiceOver label that names the axis, since the chip text alone ("Tense")
    /// doesn't say which facet it belongs to.
    func accessibilityLabel(for value: String) -> String {
        switch self {
        case .genre: "Genre: \(value)"
        case .tag: "Tag: \(value)"
        case .mood: "Mood: \(value)"
        }
    }
}

/// One tappable classification chip: its display `name` and the facet `id` the tap navigates to.
///
/// A native value type mapped at the observer boundary (`BookDetailObserver.apply`) — never a
/// bridged Kotlin object fed to a `ForEach`. Every axis carries its id so a tap can browse it:
/// genres to the genre-hierarchy screen, tags and moods to the flat facet-browse.
struct FacetChip: Identifiable, Equatable, Hashable {
    let id: String
    let name: String
}

/// A wrapping row of facet capsules for one classification axis (genre / tag / mood).
///
/// Reuses the shared ``FlowLayout`` so the chips wrap responsively to the available
/// width. The ``BookFacetKind`` switches the styling: neutral fill for genres, a
/// neutral outlined chip for tags, and a coral-accented chip with a leading symbol
/// for moods. Each chip respects Dynamic Type and labels itself sensibly for
/// VoiceOver; the leading symbols are decorative.
///
/// When `destination` is supplied, each chip becomes a `NavigationLink` that browses by id on the
/// ambient stack — matching the view's other navigations. The builder is generic over the value-typed
/// route (`GenreDestination` for genres, `FacetDestination` for tags/moods), so one component serves
/// every axis; a `nil` builder renders static, non-navigating capsules.
///
/// Pure/presentational: it takes the chips, the `kind`, and an optional destination builder.
struct BookFacetChips<Destination: Hashable>: View {
    let chips: [FacetChip]
    let kind: BookFacetKind
    var destination: ((FacetChip) -> Destination)?

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(chips) { facetChip in
                if let destination {
                    NavigationLink(value: destination(facetChip)) { chip(facetChip.name) }
                        .buttonStyle(.plain)
                } else {
                    chip(facetChip.name)
                }
            }
        }
    }

    @ViewBuilder
    private func chip(_ value: String) -> some View {
        HStack(spacing: 6) {
            if let symbolName = kind.symbolName {
                Image(systemName: symbolName)
                    .font(.caption2)
                    .accessibilityHidden(true)
            }
            Text(value)
                .font(.caption)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background {
            if kind.isOutlined {
                Capsule().strokeBorder(Color.luSeparator, lineWidth: 1.5)
            } else {
                Capsule().fill(background)
            }
        }
        .foregroundStyle(foreground)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(kind.accessibilityLabel(for: value))
        .accessibilityAddTraits(destination != nil ? .isButton : [])
    }

    /// Capsule fill: neutral system fill for genres, coral-accented for moods.
    private var background: Color {
        kind.usesAccent ? Color.listenUpOrange.opacity(0.15) : .luFill
    }

    /// Chip foreground: coral for moods, primary label for outlined tags, secondary for genres.
    private var foreground: Color {
        if kind.usesAccent { return .listenUpOrange }
        return kind.isOutlined ? .primary : .luLabel2
    }
}

// MARK: - Preview

#Preview("Facet chips — all three axes") {
    ScrollView {
        VStack(alignment: .leading, spacing: 16) {
            BookFacetChips(
                chips: ["Epic Fantasy", "Political", "Adventure"].map { FacetChip(id: $0, name: $0) },
                kind: .genre,
                destination: { GenreDestination(genreId: $0.id, genreName: $0.name) }
            )
            BookFacetChips(
                chips: ["Found Family", "Slow Burn", "Unreliable Narrator"].map { FacetChip(id: $0, name: $0) },
                kind: .tag,
                destination: { FacetDestination(kind: .tag, id: $0.id, name: $0.name) }
            )
            BookFacetChips(
                chips: ["Dark", "Epic", "Gritty", "Tense", "Atmospheric"].map { FacetChip(id: $0, name: $0) },
                kind: .mood,
                destination: { FacetDestination(kind: .mood, id: $0.id, name: $0.name) }
            )
        }
        .padding()
    }
}

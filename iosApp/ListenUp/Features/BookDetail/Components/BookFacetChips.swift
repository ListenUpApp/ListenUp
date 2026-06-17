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
/// - ``mood`` — *how it feels* to read (e.g. "Tense", "Atmospheric"). An
///   accent-tinted chip that leans on the per-book cover accent, with a leading
///   `sparkles` symbol — the most expressive of the three.
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

    /// Whether the chip leans on the per-book cover accent. Only moods do now;
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

/// A wrapping row of facet capsules for one classification axis (genre / tag / mood).
///
/// Reuses the shared ``FlowLayout`` so the chips wrap responsively to the available
/// width. The ``BookFacetKind`` switches the styling: neutral fill for genres, a
/// secondary-tinted chip with a leading symbol for tags, and an accent-tinted chip
/// (off the per-book `tint`) with a leading symbol for moods. Each chip respects
/// Dynamic Type and labels itself sensibly for VoiceOver; the leading symbols are
/// decorative.
///
/// Pure/presentational: it takes the values, the `kind`, and the per-book `tint`.
struct BookFacetChips: View {
    let values: [String]
    let kind: BookFacetKind
    /// Per-book accent, derived from cover art. Used by the tag/mood axes.
    let tint: Color

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(values, id: \.self) { value in
                chip(value)
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
    }

    /// Capsule fill: neutral system fill for genres, accent-tinted for tag/mood.
    private var background: Color {
        kind.usesAccent ? tint.opacity(0.15) : .luFill
    }

    /// Chip foreground: accent for moods, primary label for outlined tags, secondary for genres.
    private var foreground: Color {
        if kind.usesAccent { return tint }
        return kind.isOutlined ? .primary : .luLabel2
    }
}

// MARK: - Preview

#Preview("Facet chips — all three axes") {
    ScrollView {
        VStack(alignment: .leading, spacing: 16) {
            BookFacetChips(
                values: ["Epic Fantasy", "Political", "Adventure"],
                kind: .genre,
                tint: .red
            )
            BookFacetChips(
                values: ["Found Family", "Slow Burn", "Unreliable Narrator"],
                kind: .tag,
                tint: .red
            )
            BookFacetChips(
                values: ["Dark", "Epic", "Gritty", "Tense", "Atmospheric"],
                kind: .mood,
                tint: .red
            )
        }
        .padding()
    }
}

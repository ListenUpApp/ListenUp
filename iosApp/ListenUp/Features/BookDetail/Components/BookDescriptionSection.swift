import SwiftUI

/// The "Description" block on the redesigned Book Detail screen: a synopsis that
/// collapses to ~4 lines with a "more" toggle, followed by the three classification
/// facets — genres, tags, then moods.
///
/// Reuses the shared `ExpandableText` for the blurb and `BookFacetChips` for each
/// facet group. The three axes read distinctly (neutral genres, tinted tags, accent
/// moods — see `BookFacetKind`) so the reader can tell *where it lives* from *its
/// tropes* from *how it feels*. Each facet group is omitted when empty.
///
/// Pure/presentational: it takes the description text, the three facet lists, and a
/// `tint`.
struct BookDescriptionSection: View {
    let description: String
    let genres: [String]
    let tags: [String]
    let moods: [String]
    /// Per-book accent, derived from cover art.
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ExpandableText(
                title: String(localized: "common.description"),
                text: description,
                lineLimit: 4,
                titleFont: .title3.bold()
            )

            if !genres.isEmpty {
                BookFacetChips(values: genres, kind: .genre, tint: tint)
            }
            if !tags.isEmpty {
                BookFacetChips(values: tags, kind: .tag, tint: tint)
            }
            if !moods.isEmpty {
                BookFacetChips(values: moods, kind: .mood, tint: tint)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Preview

#Preview("Description — all facets") {
    ScrollView {
        BookDescriptionSection(
            description: "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod "
                + "tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis "
                + "nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis "
                + "aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat.",
            genres: ["Fantasy", "Epic", "Adventure", "Coming of Age"],
            tags: ["Found Family", "Slow Burn", "Unreliable Narrator"],
            moods: ["Dark", "Tense", "Atmospheric"],
            tint: .red
        )
        .padding()
    }
}

#Preview("Description — no facets") {
    BookDescriptionSection(
        description: "A short synopsis with no facets to show.",
        genres: [],
        tags: [],
        moods: [],
        tint: .listenUpOrange
    )
    .padding()
}

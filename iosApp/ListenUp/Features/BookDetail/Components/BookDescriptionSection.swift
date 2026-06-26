import SwiftUI

/// The "Description" block on the redesigned Book Detail screen: a synopsis that
/// collapses to ~4 lines with a "more" toggle, followed by the three classification
/// facets — genres, tags, then moods.
///
/// Reuses the shared `ExpandableText` for the blurb and `BookFacetChips` for each
/// facet group. The three axes read distinctly (neutral genres, outlined tags, coral
/// moods — see `BookFacetKind`) so the reader can tell *where it lives* from *its
/// tropes* from *how it feels*. Each facet group is omitted when empty.
///
/// Pure/presentational: it takes the description text and the three facet lists. Tag and mood
/// chips navigate to the facet-browse screen via value-typed `FacetDestination` routes on the
/// ambient stack; genres are not navigable, so they render as plain strings.
struct BookDescriptionSection: View {
    let description: String
    let genres: [String]
    let tags: [FacetChip]
    let moods: [FacetChip]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ExpandableText(
                title: String(localized: "common.description"),
                text: description,
                lineLimit: 4
            )

            if !genres.isEmpty {
                facetGroup(label: String(localized: "book.detail_facet_categories"),
                           chips: genres.map { FacetChip(id: $0, name: $0) }, kind: .genre, browseKind: nil)
            }
            if !tags.isEmpty {
                facetGroup(label: String(localized: "book.detail_facet_tags"),
                           chips: tags, kind: .tag, browseKind: .tag)
            }
            if !moods.isEmpty {
                facetGroup(label: String(localized: "book.detail_facet_moods"),
                           chips: moods, kind: .mood, browseKind: .mood)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func facetGroup(
        label: String,
        chips: [FacetChip],
        kind: BookFacetKind,
        browseKind: FacetBrowseKind?
    ) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .textCase(.uppercase)
            BookFacetChips(
                chips: chips,
                kind: kind,
                destination: browseKind.map { browse in
                    { facetChip in FacetDestination(kind: browse, id: facetChip.id, name: facetChip.name) }
                }
            )
        }
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
            tags: ["Found Family", "Slow Burn", "Unreliable Narrator"].map { FacetChip(id: $0, name: $0) },
            moods: ["Dark", "Tense", "Atmospheric"].map { FacetChip(id: $0, name: $0) }
        )
        .padding()
    }
}

#Preview("Description — no facets") {
    BookDescriptionSection(
        description: "A short synopsis with no facets to show.",
        genres: [],
        tags: [],
        moods: []
    )
    .padding()
}

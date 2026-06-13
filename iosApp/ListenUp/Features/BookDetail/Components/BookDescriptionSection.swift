import SwiftUI

/// The "Description" block on the redesigned Book Detail screen: a synopsis that
/// collapses to ~4 lines with a "more" toggle, followed by tinted genre chips.
///
/// Reuses the shared `ExpandableText` for the blurb and `FlowLayout` for the
/// chips, which take the per-book `tint` so they read as part of the same accent
/// family as the rest of the screen. The genre block is omitted when there are
/// no genres.
///
/// Pure/presentational: it takes the description text, the genres, and a `tint`.
struct BookDescriptionSection: View {
    let description: String
    let genres: [String]
    /// Per-book accent, derived from cover art.
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ExpandableText(
                title: String(localized: "common.description"),
                text: description,
                lineLimit: 4
            )

            if !genres.isEmpty {
                genreChips
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var genreChips: some View {
        FlowLayout(spacing: 8) {
            ForEach(genres, id: \.self) { genre in
                Text(genre)
                    .font(.caption)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(tint.opacity(0.15), in: Capsule())
                    .foregroundStyle(tint)
            }
        }
    }
}

// MARK: - Preview

#Preview("Description — with genres") {
    ScrollView {
        BookDescriptionSection(
            description: "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod "
                + "tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis "
                + "nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis "
                + "aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat.",
            genres: ["Fantasy", "Epic", "Adventure", "Coming of Age"],
            tint: .red
        )
        .padding()
    }
}

#Preview("Description — no genres") {
    BookDescriptionSection(
        description: "A short synopsis with no genres to show.",
        genres: [],
        tint: .listenUpOrange
    )
    .padding()
}

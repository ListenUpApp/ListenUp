import SwiftUI

/// Centered hero for the redesigned Book Detail screen.
///
/// Renders the cover, the book's series paths (one line per series, every name a link), the title, author line, a
/// tappable "Narrated by …" line, and a secondary foot line ("{N} chapters ·
/// {duration} · {year}", omitting any absent segment). Interactive accents use the
/// app's coral action tint.
///
/// Pure/presentational: it takes display values and navigates via value-typed routes
/// (`SeriesDestination`, `ContributorDestination`). The assembly screen wires it to
/// `BookDetailObserver`.
struct BookDetailHero: View {
    /// The book's cover-lookup fields, projected to a
    /// native value at the observer boundary so the hero never re-bridges the Kotlin object.
    let header: BookDetailHeaderModel?
    let title: String
    /// Optional book subtitle, shown under the title when present.
    let subtitle: String?
    /// One line per series — "Cosmere › Mistborn › Mistborn Era 1 #1". None for a standalone book.
    let seriesPaths: [BookSeriesPathItem]
    /// Tappable author chips; falls back to `author` text when empty.
    let authors: [CastMember]
    /// Plain authors string for the no-contributors fallback.
    let author: String
    /// Tappable narrator chips; falls back to `narratorsText` when empty.
    let narrators: [CastMember]
    /// Plain narrators string for the VoiceOver summary and the no-contributors fallback.
    let narratorsText: String
    let chapterCount: Int
    let duration: String
    let year: Int?
    /// Opens the Cast & Credits sheet when a category collapses past the inline limit.
    let onOpenCast: () -> Void

    /// Paths the reader has unfolded with "…".
    @State private var expandedPaths: Set<String> = []

    private let inlineContributorLimit = 2
    private let coverSize: CGFloat = 196

    var body: some View {
        VStack(spacing: 0) {
            cover

            // Outside the combined summary below, so each crumb stays its own VoiceOver link.
            if !seriesPaths.isEmpty {
                seriesPathLines
                    .padding(.top, Spacing.m)
            }

            summary
        }
        .frame(maxWidth: .infinity)
    }

    private var summary: some View {
        VStack(spacing: 0) {
            Text(title)
                .font(.title2.bold())
                .multilineTextAlignment(.center)
                .padding(.top, Spacing.s)

            if let subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, Spacing.xxs)
            }

            authorsLine
                .padding(.top, Spacing.xs)

            narratorsLine
                .padding(.top, 2)

            if let foot = footLine {
                Text(foot)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.top, Spacing.xs)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilitySummary)
    }

    // MARK: - Cover

    private var cover: some View {
        ZStack {
            // A soft halo of the cover's own colours, contained directly behind the crisp cover.
            CoverGlow(
                bookId: header?.coverBookId,
                coverPath: header?.coverPath,
                coverHash: header?.coverHash,
                size: coverSize
            )

            BookCoverImage(
                bookId: header?.coverBookId,
                coverPath: header?.coverPath,
                coverHash: header?.coverHash
            )
                .frame(width: coverSize, height: coverSize)
                .clipShape(RoundedRectangle(cornerRadius: Radius.xl, style: .continuous))
                .shadow(color: .black.opacity(0.18), radius: 16, x: 0, y: 8)
        }
        .accessibilityHidden(true)
    }

    // MARK: - Series paths

    /// Stacked, one per series; each wraps only at its separators and never truncates a name.
    private var seriesPathLines: some View {
        VStack(spacing: 0) {
            ForEach(seriesPaths) { path in
                SeriesPathView(
                    parts: path.parts(expanded: expandedPaths.contains(path.id)),
                    onExpand: { expandedPaths.insert(path.id) }
                )
            }
        }
    }

    // MARK: - Authors

    @ViewBuilder
    private var authorsLine: some View {
        if authors.count > inlineContributorLimit,
           let summary = collapsedContributorSummary(names: authors.map(\.name), limit: inlineContributorLimit) {
            collapsedLine(prefix: nil, summary: summary)
                .font(.callout)
        } else if !authors.isEmpty {
            contributorChips(prefix: nil, contributors: authors)
                .font(.callout)
        } else if !author.isEmpty {
            Text(author)
                .font(.callout)
                .multilineTextAlignment(.center)
        }
    }

    // MARK: - Narrators

    @ViewBuilder
    private var narratorsLine: some View {
        if narrators.count > inlineContributorLimit,
           let summary = collapsedContributorSummary(names: narrators.map(\.name), limit: inlineContributorLimit) {
            collapsedLine(prefix: String(localized: "book.detail_narrated_by_prefix"), summary: summary)
                .font(.subheadline)
        } else if !narrators.isEmpty {
            contributorChips(
                prefix: String(localized: "book.detail_narrated_by_prefix"),
                contributors: narrators
            )
            .font(.subheadline)
        } else if !narratorsText.isEmpty {
            Text(String(format: String(localized: "book.detail_narrated_by_value"), narratorsText))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    /// A centered, tappable "{prefix}{first} & N others" line that opens the Cast sheet
    /// when a category has more contributors than the inline limit.
    private func collapsedLine(prefix: String?, summary: String) -> some View {
        var line = AttributedString()
        if let prefix {
            var prefixRun = AttributedString(prefix)
            prefixRun.foregroundColor = .secondary
            line += prefixRun
        }
        var summaryRun = AttributedString(summary)
        summaryRun.foregroundColor = .listenUpOrange
        line += summaryRun
        return Button(action: onOpenCast) {
            Text(line)
        }
        .buttonStyle(.plain)
        .multilineTextAlignment(.center)
        .accessibilityHint(Text(String(localized: "book.detail_credits_hint")))
    }

    /// A centered, wrapping run of tappable contributor names (each links to its detail),
    /// optionally led by a secondary `prefix` (e.g. "Narrated by "). Centering comes from
    /// `FlowLayout(alignment: .center)` — `.multilineTextAlignment` does not affect a custom layout.
    @ViewBuilder
    private func contributorChips(prefix: String?, contributors: [CastMember]) -> some View {
        FlowLayout(spacing: 0, alignment: .center) {
            if let prefix {
                Text(prefix).foregroundStyle(.secondary)
            }
            ForEach(Array(contributors.enumerated()), id: \.element.id) { index, contributor in
                HStack(spacing: 0) {
                    if index > 0 {
                        Text(", ").foregroundStyle(Color.listenUpOrange)
                    }
                    NavigationLink(value: ContributorDestination(id: contributor.id)) {
                        Text(contributor.name).foregroundStyle(Color.listenUpOrange)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Foot line

    /// "{N} chapters · {duration} · {year}", dropping any absent segment.
    private var footLine: String? {
        var segments: [String] = []
        if chapterCount > 0 {
            segments.append(String(format: String(localized: "book.detail_chapter_count"), chapterCount))
        }
        if !duration.isEmpty {
            segments.append(duration)
        }
        if let year {
            segments.append(String(year))
        }
        return segments.isEmpty ? nil : segments.joined(separator: " · ")
    }

    // MARK: - Accessibility

    private var accessibilitySummary: String {
        var parts: [String] = [title]
        if let subtitle, !subtitle.isEmpty { parts.append(subtitle) }
        if !author.isEmpty { parts.append(author) }
        if !narratorsText.isEmpty {
            parts.append(String(format: String(localized: "book.detail_narrated_by_value"), narratorsText))
        }
        return parts.joined(separator: ", ")
    }
}

// MARK: - Preview

#Preview("Hero") {
    // `header` is nil so no cover loads, and the narrator chips fall back to the plain
    // text path.
    ScrollView {
        VStack(spacing: 40) {
            BookDetailHero(
                header: nil,
                title: "A Game of Thrones",
                subtitle: "A Song of Ice and Fire, Book One",
                seriesPaths: [
                    BookSeriesPathItem(
                        seriesId: "s", seriesName: "A Song of Ice and Fire", sequence: "1", ancestors: []
                    )
                ],
                authors: [],
                author: "George R.R. Martin",
                narrators: [],
                narratorsText: "Roy Dotrice",
                chapterCount: 23,
                duration: "33h 50m",
                year: 2003,
                onOpenCast: {}
            )

            BookDetailHero(
                header: nil,
                title: "The Way of Kings",
                subtitle: nil,
                seriesPaths: [],
                authors: [],
                author: "Brandon Sanderson",
                narrators: [],
                narratorsText: "Kate Reading, Michael Kramer",
                chapterCount: 0,
                duration: "45h 30m",
                year: nil,
                onOpenCast: {}
            )
        }
        .padding()
    }
}

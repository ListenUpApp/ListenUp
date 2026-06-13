import SwiftUI
@preconcurrency import Shared

/// Centered hero for the redesigned Book Detail screen.
///
/// Renders the cover, an optional tappable series pill (tinted with the per-book
/// accent), the title, author line, a tappable "Narrated by …" line, and a
/// secondary foot line ("{N} chapters · {duration} · {year}", omitting any
/// absent segment).
///
/// Pure/presentational: it takes display values plus a `tint` and navigates via
/// value-typed routes (`SeriesDestination`, `ContributorDestination`). The
/// assembly screen wires it to `BookDetailObserver`.
struct BookDetailHero: View {
    /// The book, used only to resolve the series-pill navigation target.
    let book: BookDetail?
    let title: String
    /// Pre-formatted series label (e.g. "A Song of Ice and Fire · Book 1").
    /// The series pill is omitted when this is `nil`.
    let series: String?
    let author: String
    /// Tappable narrator chips; falls back to `narratorsText` when empty.
    let narrators: [BookContributor]
    /// Plain narrators string for the VoiceOver summary and the no-contributors fallback.
    let narratorsText: String
    let chapterCount: Int
    let duration: String
    let year: Int?
    /// Per-book accent, derived from cover art.
    let tint: Color

    private let coverSize: CGFloat = 196

    var body: some View {
        VStack(spacing: 0) {
            cover

            if let series {
                seriesPill(series)
                    .padding(.top, 18)
            }

            Text(title)
                .font(.title2.bold())
                .multilineTextAlignment(.center)
                .padding(.top, 12)

            if !author.isEmpty {
                Text(author)
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .padding(.top, 6)
            }

            narratorsLine
                .padding(.top, 2)

            if let foot = footLine {
                Text(foot)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilitySummary)
    }

    // MARK: - Cover

    private var cover: some View {
        BookCoverImage(coverPath: book?.coverPath, blurHash: book?.coverBlurHash)
            .frame(width: coverSize, height: coverSize)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .shadow(color: .black.opacity(0.18), radius: 16, x: 0, y: 8)
            .accessibilityHidden(true)
    }

    // MARK: - Series pill

    private func seriesPill(_ series: String) -> some View {
        NavigationLink(value: SeriesDestination(id: book?.seriesId ?? "")) {
            HStack(spacing: 6) {
                Image(systemName: "book")
                    .font(.caption2.weight(.semibold))
                Text(series)
                    .font(.caption.weight(.semibold))
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 6)
            .foregroundStyle(tint)
            .background(tint.opacity(0.12), in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(String(format: String(localized: "book.detail_series_pill_a11y"), series)))
    }

    // MARK: - Narrators

    @ViewBuilder
    private var narratorsLine: some View {
        if narrators.isEmpty {
            if !narratorsText.isEmpty {
                Text(String(format: String(localized: "book.detail_narrated_by_value"), narratorsText))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        } else {
            // "Narrated by " prefix + comma-separated tappable narrator names.
            FlowLayout(spacing: 0) {
                Text(String(localized: "book.detail_narrated_by_prefix"))
                    .foregroundStyle(.secondary)

                ForEach(Array(narrators.enumerated()), id: \.element.id) { index, narrator in
                    HStack(spacing: 0) {
                        if index > 0 {
                            Text(", ").foregroundStyle(tint)
                        }
                        NavigationLink(value: ContributorDestination(id: narrator.id)) {
                            Text(narrator.name).foregroundStyle(tint)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .font(.subheadline)
            .multilineTextAlignment(.center)
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
        if !author.isEmpty { parts.append(author) }
        if !narratorsText.isEmpty {
            parts.append(String(format: String(localized: "book.detail_narrated_by_value"), narratorsText))
        }
        return parts.joined(separator: ", ")
    }
}

// MARK: - Preview

#Preview("Hero — tints") {
    // Kotlin objects can't be constructed in previews, so `book` is nil and the
    // narrator chips fall back to the plain text path; both tints are shown.
    ScrollView {
        VStack(spacing: 40) {
            BookDetailHero(
                book: nil,
                title: "A Game of Thrones",
                series: "A Song of Ice and Fire · Book 1",
                author: "George R.R. Martin",
                narrators: [],
                narratorsText: "Roy Dotrice",
                chapterCount: 23,
                duration: "33h 50m",
                year: 2003,
                tint: .red
            )

            BookDetailHero(
                book: nil,
                title: "The Way of Kings",
                series: nil,
                author: "Brandon Sanderson",
                narrators: [],
                narratorsText: "Kate Reading, Michael Kramer",
                chapterCount: 0,
                duration: "45h 30m",
                year: nil,
                tint: .listenUpOrange
            )
        }
        .padding()
    }
}

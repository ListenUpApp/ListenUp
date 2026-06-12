import SwiftUI
@preconcurrency import Shared

/// The "Chapters" block on the redesigned Book Detail screen: a heading with a
/// trailing "All {N}" control that toggles between a five-chapter preview and the
/// full list, then flat display-only rows.
///
/// Each row shows a tabular 1-based number, the title, and a pre-formatted
/// duration. The currently-playing chapter (`chapter.isCurrent`) renders in
/// `tint` with a `pause.fill` glyph and semibold weight; the rest stay neutral.
/// Rows are plain content — never buttons or links — because this screen lists
/// chapters, it does not navigate into them.
///
/// Pure/presentational: it takes the chapters and a `tint`.
struct BookChaptersSection: View {
    let chapters: [ChapterUiModel]
    /// Per-book accent, derived from cover art.
    let tint: Color

    @State private var showAll = false

    private let previewCount = 5

    private var visibleChapters: [ChapterUiModel] {
        showAll ? chapters : Array(chapters.prefix(previewCount))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.bottom, 8)

            ForEach(Array(visibleChapters.enumerated()), id: \.element.id) { index, chapter in
                if index > 0 {
                    Divider()
                }
                chapterRow(number: index + 1, chapter: chapter)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Header

    private var header: some View {
        HStack {
            Text(String(localized: "book.detail_chapters"))
                .font(.headline)

            Spacer()

            if chapters.count > previewCount {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { showAll.toggle() }
                } label: {
                    Text(showAll
                        ? String(localized: "book.detail_show_less")
                        : String(format: String(localized: "book.detail_all_count"), chapters.count))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(tint)
                }
            }
        }
    }

    // MARK: - Row

    private func chapterRow(number: Int, chapter: ChapterUiModel) -> some View {
        HStack(spacing: 12) {
            if chapter.isCurrent {
                Image(systemName: "pause.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(tint)
                    .frame(width: 24)
            } else {
                Text("\(number)")
                    .font(.caption.weight(.medium).monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 24)
            }

            Text(chapter.title)
                .font(.subheadline.weight(chapter.isCurrent ? .semibold : .regular))
                .foregroundStyle(chapter.isCurrent ? tint : .primary)
                .lineLimit(1)

            Spacer(minLength: 12)

            Text(chapter.duration)
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 11)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Preview

#Preview("Chapters — empty (Kotlin model not previewable)") {
    // `ChapterUiModel` is a Kotlin data class and can't be constructed in a
    // SwiftUI preview, so this previews the empty-list path (header only).
    BookChaptersSection(chapters: [], tint: .red)
        .padding()
}

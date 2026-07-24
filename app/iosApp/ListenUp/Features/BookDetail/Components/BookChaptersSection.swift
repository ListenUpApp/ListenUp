import SwiftUI

/// The "Chapters" block on the redesigned Book Detail screen: a heading with a
/// trailing "All {N}" control that toggles between a five-chapter preview and the
/// full list, then flat display-only rows.
///
/// Each row shows a tabular 1-based number, the title, and a pre-formatted
/// duration. The currently-playing chapter (`chapter.isCurrent`) renders in coral
/// with a `pause.fill` glyph and semibold weight; the rest stay neutral. Rows are
/// plain content — never buttons or links — because this screen lists chapters, it
/// does not navigate into them.
///
/// Pure/presentational: it takes the chapters.
struct BookChaptersSection: View {
    let chapters: [BookChapterRow]

    @State private var showAll = false

    private let previewCount = 5

    private var visibleChapters: [BookChapterRow] {
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
                        .foregroundStyle(Color.listenUpOrange)
                }
            }
        }
    }

    // MARK: - Row

    private func chapterRow(number: Int, chapter: BookChapterRow) -> some View {
        HStack(spacing: 12) {
            if chapter.isCurrent {
                Image(systemName: "pause.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.listenUpOrange)
                    .frame(width: 24)
            } else {
                Text("\(number)")
                    .font(.caption.weight(.medium).monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 24)
            }

            Text(chapter.title)
                .font(.subheadline.weight(chapter.isCurrent ? .semibold : .regular))
                .foregroundStyle(chapter.isCurrent ? Color.listenUpOrange : .primary)
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

#Preview("Chapters") {
    BookChaptersSection(
        chapters: [
            BookChapterRow(id: "1", title: "Prologue", duration: "4:21", isCurrent: false),
            BookChapterRow(id: "2", title: "An Unexpected Party", duration: "38:02", isCurrent: true),
            BookChapterRow(id: "3", title: "Roast Mutton", duration: "29:14", isCurrent: false)
        ]
    )
    .padding()
}

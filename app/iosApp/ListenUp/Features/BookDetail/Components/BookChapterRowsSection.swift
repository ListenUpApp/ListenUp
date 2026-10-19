import SwiftUI

/// Chapters that play from the chapter they name (spec §2.5). Collapsed to the 5-row window around the current
/// chapter (`preview`); "All N Chapters" expands in place. Each row is a button named "Play from chapter N: title";
/// the current row is selected and tinted. Rows are disabled whenever the book can't play.
///
/// Pure/presentational: it takes the projected rows and reports a tap with the chapter's start.
struct BookChapterRowsSection: View {
    let chapters: [BookChapterRow]
    let preview: [BookChapterRow]
    let canPlay: Bool
    let onPlayFromChapter: (Int64) -> Void

    @State private var expanded = false

    private var shown: [BookChapterRow] { expanded ? chapters : preview }

    /// A chapter's number is its place in the whole book, not in the window.
    private var numbers: [String: Int] {
        Dictionary(chapters.enumerated().map { ($1.id, $0 + 1) }, uniquingKeysWith: { first, _ in first })
    }

    var body: some View {
        let numbers = numbers
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(alignment: .firstTextBaseline) {
                Text(String(localized: "book.detail_chapters"))
                    .font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Text(chapters.count, format: .number)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, Spacing.xxs)

            VStack(spacing: 0) {
                ForEach(shown) { chapter in
                    row(chapter, number: numbers[chapter.id] ?? 0)
                    if chapter.id != shown.last?.id {
                        Divider().padding(.leading, 52)
                    }
                }
                if !expanded && chapters.count > preview.count {
                    Divider()
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) { expanded = true }
                    } label: {
                        Text(String(format: String(localized: "book.detail_all_chapters_ios"), chapters.count))
                            .frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.listenUpOrange)
                    .padding(.horizontal, Spacing.m)
                }
            }
            .background(
                Color(.secondarySystemGroupedBackground),
                in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func row(_ chapter: BookChapterRow, number: Int) -> some View {
        Button {
            onPlayFromChapter(chapter.startMs)
        } label: {
            HStack(spacing: 12) {
                Text(number, format: .number)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(minWidth: 24, alignment: .leading)
                Text(chapter.title)
                    .font(.body.weight(chapter.isCurrent ? .semibold : .regular))
                    .foregroundStyle(chapter.isCurrent ? Color.listenUpOrange : .primary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(chapter.duration)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
                Image(systemName: "play.fill")
                    .font(.footnote)
                    .foregroundStyle(Color.listenUpOrange)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 50)
            .padding(.horizontal, Spacing.m)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressScaleRow)
        .disabled(!canPlay)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            String(format: String(localized: "book.detail_play_from_chapter_a11y"), number, chapter.title)
        )
        // The current chapter is marked by colour and weight; VoiceOver gets it as state (HIG, Accessibility).
        .accessibilityAddTraits(chapter.isCurrent ? [.isButton, .isSelected] : .isButton)
    }
}

// MARK: - Preview

#Preview("Chapters") {
    let rows = (0..<12).map {
        BookChapterRow(id: "c\($0)", title: "Chapter \($0 + 1)", duration: "10:00", isCurrent: $0 == 5,
                       startMs: Int64($0) * 600_000)
    }
    ScrollView {
        BookChapterRowsSection(chapters: rows, preview: Array(rows[3..<8]), canPlay: true, onPlayFromChapter: { _ in })
            .padding()
    }
}

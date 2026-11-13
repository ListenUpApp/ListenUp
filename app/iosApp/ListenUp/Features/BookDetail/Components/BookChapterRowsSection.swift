import SwiftUI

/// Chapters that play from the chapter they name. Collapsed to the 5-row window around the current
/// chapter (`preview`); "All N Chapters" expands in place and "Fewer Chapters" collapses it. Each row is a button named "Play from chapter N: title";
/// the current row is selected and tinted. Rows are disabled whenever the book can't play.
///
/// Everyone else reading the book shows as a stack of avatars on the row of the chapter they are on; a collapsed part
/// carries the stack of everyone in it, and the collapsed list's "All N Chapters" everyone on a row it hides. VoiceOver
/// hears them as the row's value, so each row stays one element whose label is still its action.
///
/// Pure/presentational: it takes the projected rows and reports a tap with the chapter's start.
struct BookChapterRowsSection: View {
    let chapters: [BookChapterRow]
    let preview: [BookChapterRow]
    /// A marked book's outline. Nil for a flat book, which draws exactly what it always has.
    let grouped: BookGroupedChapters?
    let canPlay: Bool
    /// Where everyone else is in the book.
    let readers: ChapterReadersLookup
    let onPlayFromChapter: (Int64) -> Void

    @State private var expanded: Bool
    /// How the reader left each part, for as long as this screen is up.
    @State private var partOverrides: [String: Bool] = [:]

    init(chapters: [BookChapterRow], preview: [BookChapterRow], grouped: BookGroupedChapters? = nil, canPlay: Bool,
         readers: ChapterReadersLookup = .none, initiallyExpanded: Bool = false,
         onPlayFromChapter: @escaping (Int64) -> Void) {
        self.chapters = chapters
        self.preview = preview
        self.grouped = grouped
        self.canPlay = canPlay
        self.readers = readers
        self.onPlayFromChapter = onPlayFromChapter
        _expanded = State(initialValue: initiallyExpanded)
    }

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
                if let grouped {
                    outline(grouped)
                } else {
                    ForEach(shown) { chapter in
                        row(chapter, number: numbers[chapter.id] ?? 0)
                        if chapter.id != shown.last?.id {
                            Divider().padding(.leading, 52)
                        }
                    }
                }
                // Expands in place, and collapses again.
                if chapters.count > preview.count {
                    Divider()
                    // Collapsed, it carries everyone on a chapter whose row it hides.
                    let hidden = expanded ? nil : readers.beyond(shownChapterIds(grouped))
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
                    } label: {
                        HStack(spacing: 12) {
                            Text(expanded
                                 ? String(localized: "book.detail_fewer_chapters_ios")
                                 : String(format: String(localized: "book.detail_all_chapters_ios"), chapters.count))
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if let hidden {
                                ChapterReadersStackView(stack: hidden)
                            }
                        }
                        .frame(minHeight: 50)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.listenUpOrange)
                    .padding(.horizontal, Spacing.m)
                    .accessibilityValue(hidden?.sentence ?? "")
                }
            }
            .background(
                Color(.secondarySystemGroupedBackground),
                in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The chapters the collapsed list shows a row for: the window's, and in a marked book also each chapter folded
    /// into a part header the window shows.
    private func shownChapterIds(_ grouped: BookGroupedChapters?) -> [String] {
        guard let grouped else { return preview.map(\.id) }
        return grouped.previewRows.compactMap { row in
            switch row {
            case .part(let part): part.chapterId
            case .chapter(let number, _): chapters.indices.contains(number - 1) ? chapters[number - 1].id : nil
            case .book: nil
            }
        }
    }

    /// The window with the part it sits in named above it, or with `expanded` the whole outline.
    @ViewBuilder
    private func outline(_ grouped: BookGroupedChapters) -> some View {
        if !expanded, let label = grouped.previewLabel {
            groupLabel(label)
        }
        let rows = expanded ? grouped.visibleRows(overrides: partOverrides) : grouped.previewRows
        ForEach(rows) { row in
            outlineRow(row)
            if BookGroupedChapters.hasDivider(after: row, in: rows) {
                Divider().padding(.leading, 52)
            }
        }
    }

    @ViewBuilder
    private func outlineRow(_ row: BookOutlineRow) -> some View {
        switch row {
        case .book(let title, _):
            groupLabel(title)
        case .part(let part):
            let isOpen = expanded && part.chapterCount > 0 ? part.isOpen(overrides: partOverrides) : nil
            BookPartRow(
                part: part,
                isOpen: isOpen,
                canPlay: canPlay,
                // A closed part carries everyone in it; otherwise the header is the chapter folded into it.
                readers: isOpen == false ? readers.inPart(part.chapterId) : readers.onChapter(part.chapterId),
                onToggle: {
                    withAnimation(.easeInOut(duration: 0.2)) { partOverrides = part.toggled(partOverrides) }
                },
                onPlay: { onPlayFromChapter(part.startMs) }
            )
        case .chapter(let number, _):
            // The outline's number is the chapter's place in the whole book, which indexes `chapters`.
            if chapters.indices.contains(number - 1) {
                self.row(chapters[number - 1], number: number)
            }
        }
    }

    /// The part or book a window starts inside, or a book's own header: a heading, not a control.
    private func groupLabel(_ title: String) -> some View {
        Text(title)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Spacing.m)
            .padding(.top, Spacing.s)
            .padding(.bottom, Spacing.xxs)
            .accessibilityAddTraits(.isHeader)
    }

    private func row(_ chapter: BookChapterRow, number: Int) -> some View {
        let onChapter = readers.onChapter(chapter.id)
        return Button {
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
                if let stack = onChapter {
                    ChapterReadersStackView(stack: stack)
                }
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
        // Who is here is the row's state; its label stays the action (HIG, Accessibility).
        .accessibilityValue(onChapter?.sentence ?? "")
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

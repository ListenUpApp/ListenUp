import SwiftUI

/// Chapters that play from the chapter they name. Collapsed to the 5-row window around the current
/// chapter (`preview`); "All N Chapters" expands in place and "Fewer Chapters" collapses it. Each row is a button named
/// "Play from chapter N: title"; the current row is selected and tinted. Rows are disabled whenever the book can't play.
///
/// A marked book draws every chapter as a row, as the audiobook has them, in one inset card per part: the gap between
/// cards is the break, and the part's heading above its card names it ("Part IV: Reaper", "13 chapters · 5 hr, 16 min").
/// A rule inside one card would read as just another row separator. An omnibus puts each book's heading above its
/// parts. Headings are headings, never controls and never current; the collapsed window shows one wherever it starts
/// inside a part or crosses into another.
///
/// Everyone else reading the book shows as a stack of avatars on the row of the chapter they are on, and the collapsed
/// list's "All N Chapters" carries everyone on a row it hides. VoiceOver hears them as the row's value, so each row
/// stays one element whose label is still its action.
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

    private var hasMore: Bool { chapters.count > preview.count }

    var body: some View {
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

            if let grouped {
                outline(grouped)
            } else {
                let numbers = numbers
                VStack(spacing: 0) {
                    ForEach(shown) { chapter in
                        row(chapter, number: numbers[chapter.id] ?? 0)
                        if chapter.id != shown.last?.id {
                            Divider().padding(.leading, 52)
                        }
                    }
                    allChaptersButton(shownChapterIds: preview.map(\.id))
                }
                .chapterCard()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The window, or with `expanded` the whole outline, as one card per part under its heading.
    private func outline(_ grouped: BookGroupedChapters) -> some View {
        let sections = BookGroupedChapters.sections(expanded ? grouped.rows : grouped.previewRows)
        let shownChapterIds = grouped.previewRows.compactMap { row -> String? in
            guard case .chapter(let index, _) = row, chapters.indices.contains(index) else { return nil }
            return chapters[index].id
        }
        return VStack(alignment: .leading, spacing: 0) {
            ForEach(sections) { section in
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    if let book = section.book {
                        bookHeading(book)
                    }
                    if let part = section.part {
                        partHeading(part)
                    }
                    VStack(spacing: 0) {
                        ForEach(section.chapters, id: \.index) { chapter in
                            if chapters.indices.contains(chapter.index) {
                                row(chapters[chapter.index], number: chapter.number)
                                if chapter != section.chapters.last {
                                    Divider().padding(.leading, 52)
                                }
                            }
                        }
                        // "All N Chapters" closes the last card, as a flat book's closes its only one.
                        if section.id == sections.last?.id {
                            allChaptersButton(shownChapterIds: shownChapterIds)
                        }
                    }
                    .chapterCard()
                }
                .padding(.top, topSpace(section, isFirst: section.id == sections.first?.id))
            }
        }
    }

    /// Generous space above a part, more above a book: the break has to read at a fling. The first heading sits
    /// just under "Chapters".
    private func topSpace(_ section: BookOutlineSection, isFirst: Bool) -> CGFloat {
        if isFirst { return Spacing.xxs }
        return section.book == nil ? Spacing.xxl : Spacing.xxl + Spacing.xs
    }

    /// An omnibus book's heading, a level above its parts'.
    private func bookHeading(_ title: String) -> some View {
        Text(title)
            .font(.title.bold())
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Spacing.xxs)
            .accessibilityAddTraits(.isHeader)
    }

    /// A part's heading: its title, and under it the quiet count and length. VoiceOver reads it as one heading, so the
    /// headings rotor walks the parts.
    private func partHeading(_ part: BookPartHeader) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(part.title)
                .font(.title2.bold())
            Text(part.summary)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Spacing.xxs)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(part.spokenName)
        .accessibilityAddTraits(.isHeader)
    }

    /// Expands in place, and collapses again. Collapsed, it carries everyone on a chapter whose row it hides.
    @ViewBuilder
    private func allChaptersButton(shownChapterIds: [String]) -> some View {
        if hasMore {
            Divider()
            let hidden = expanded ? nil : readers.beyond(shownChapterIds)
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

private extension View {
    /// The inset card a run of chapter rows sits in.
    func chapterCard() -> some View {
        background(
            Color(.secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
        )
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

#Preview("Parts") {
    let rows = (0..<8).map {
        BookChapterRow(id: "c\($0)", title: $0 == 1 ? "Part I" : "Chapter \($0)", duration: "10:00",
                       isCurrent: $0 == 3, startMs: Int64($0) * 600_000)
    }
    let partOne = BookPartHeader(title: "Part I: Slave", chapterId: "c1", chapterCount: 4, durationMs: 2_400_000)
    let partTwo = BookPartHeader(title: "Part II: Reborn", chapterId: "c5", chapterCount: 3, durationMs: 1_800_000)
    let outline: [BookOutlineRow] = [.chapter(index: 0, number: 1), .part(partOne)]
        + (1...4).map { .chapter(index: $0, number: $0 + 1) }
        + [.part(partTwo)] + (5...7).map { .chapter(index: $0, number: $0 + 1) }
    ScrollView {
        BookChapterRowsSection(chapters: rows, preview: Array(rows[1..<6]),
                               grouped: BookGroupedChapters(rows: outline, previewRows: Array(outline[1...7])),
                               canPlay: true, initiallyExpanded: true, onPlayFromChapter: { _ in })
            .padding()
    }
}

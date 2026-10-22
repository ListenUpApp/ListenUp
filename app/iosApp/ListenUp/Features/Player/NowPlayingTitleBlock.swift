import SwiftUI

/// Now Playing's title block (player spec §5.2, iOS): the chapter line, the book's title, and a byline whose names are
/// links. More than three people in a role fold to "Michael Kramer & 3 more", a pull-down menu of the names (HIG,
/// Pull-down buttons: a menu for a navigation choice). Titles wrap rather than truncate as text grows (HIG,
/// Typography: "aim to display as much useful text at the largest accessibility font size as you do at the largest
/// standard size").
struct NowPlayingTitleBlock: View {
    let observer: PlayerObserver
    /// Dismiss Now Playing and open the contributor.
    let onViewContributor: (String) -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// More than this many names in a role fold behind a menu.
    static let foldThreshold = 3

    /// "Chapter 6 of 75 · Bridge Four": the counter only where it cannot disagree with the book's own naming (plan C6);
    /// the book's own title alone otherwise; "Chapter 6" for an untitled chapter; "Whole book" for a chapterless book.
    static func chapterLine(number: Int, total: Int, title: String, showsCounter: Bool, isWholeBook: Bool) -> String {
        if isWholeBook { return String(localized: "player.np_whole_book") }
        guard !title.isEmpty else { return String(format: String(localized: "player.chapter_number"), number) }
        guard showsCounter else { return title }
        return String(format: String(localized: "player.chapter_of"), "\(number)", "\(total)") + " · " + title
    }

    /// "Narrator 1 & 4 more" for more than three people, else nil (the names are listed).
    static func folded(_ people: [ContributorNavRef]) -> String? {
        guard people.count > foldThreshold, let lead = people.first else { return nil }
        return String(format: String(localized: "player.np_byline_fold"), lead.name, people.count - 1)
    }

    var body: some View {
        let isLarge = dynamicTypeSize.isAccessibilitySize
        VStack(alignment: .leading, spacing: 3) {
            Text(Self.chapterLine(
                number: observer.chapterIndex + 1,
                total: observer.totalChapters,
                title: observer.chapterTitle ?? "",
                showsCounter: observer.showsChapterCounter,
                isWholeBook: observer.isWholeBook
            ))
            .font(.callout)
            .foregroundStyle(.secondary)
            .lineLimit(isLarge ? 3 : 1)

            Text(observer.bookTitle)
                .font(.title2.bold())
                .foregroundStyle(.primary)
                .lineLimit(isLarge ? 3 : 2)
                .accessibilityAddTraits(.isHeader)

            byline(String(localized: "player.np_by"), observer.authors)
            byline(String(localized: "player.np_read_by"), observer.narrators)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private func byline(_ lead: String, _ people: [ContributorNavRef]) -> some View {
        if !people.isEmpty {
            FlowLayout(spacing: 4) {
                Text(lead)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                if let folded = Self.folded(people) {
                    Menu {
                        ForEach(people) { person in
                            Button(person.name) { onViewContributor(person.id) }
                        }
                    } label: {
                        link(folded)
                    }
                    .accessibilityLabel(lead + " " + folded)
                } else {
                    ForEach(Array(people.enumerated()), id: \.element.id) { offset, person in
                        Button { onViewContributor(person.id) } label: {
                            link(person.name + (offset < people.count - 1 ? "," : ""))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(lead + " " + person.name)
                        .accessibilityAddTraits(.isLink)
                    }
                }
            }
        }
    }

    private func link(_ text: String) -> some View {
        Text(text)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(Color.luTintInk)
            // A 44pt hit area around a text-sized link, without spacing the byline out (HIG, Accessibility).
            .minimumTapTarget(visualSize: 20)
    }
}

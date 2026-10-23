import SwiftUI

/// One person in the byline's folded menu: their name and their books in your library (spec §5.5.5, PK1).
struct BylineMenuRow: Equatable, Identifiable {
    let id: String
    let name: String
    /// "14 books"; nil when the library has none counted, so a zero never shows.
    let books: String?
}

/// Now Playing's title block (player spec §5.2, iOS; the `NP-iPhone` board): the book's title, the author and "Read by"
/// line whose names are links, then the chapter line. More than three people in a role fold to "Michael Kramer & 3 more", a pull-down menu of the names (HIG,
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

    /// The folded menu's rows: each name with its book count as the menu item's subtitle.
    static func menuRows(_ people: [ContributorNavRef]) -> [BylineMenuRow] {
        people.map { person in
            let books: String? = switch person.bookCount {
            case ...0: nil
            case 1: String(localized: "player.sheet_books_one_ios")
            default: String(format: String(localized: "player.sheet_books_other_ios"), person.bookCount)
            }
            return BylineMenuRow(id: person.id, name: person.name, books: books)
        }
    }

    /// The folded menu's section header: the role its names fill.
    static func menuHeader(isAuthors: Bool) -> String {
        isAuthors ? String(localized: "player.sheet_authors") : String(localized: "player.sheet_narrators")
    }

    var body: some View {
        let isLarge = dynamicTypeSize.isAccessibilitySize
        VStack(alignment: .leading, spacing: 3) {
            Text(observer.bookTitle)
                .font(.title2.bold())
                .foregroundStyle(.primary)
                .lineLimit(isLarge ? 3 : 2)
                .accessibilityAddTraits(.isHeader)

            byline(lead: nil, observer.authors)
            byline(lead: String(localized: "player.np_read_by"), observer.narrators)

            Text(Self.chapterLine(
                number: observer.chapterIndex + 1,
                total: observer.totalChapters,
                title: observer.chapterTitle ?? "",
                showsCounter: observer.showsChapterCounter,
                isWholeBook: observer.isWholeBook
            ))
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.primary)
            .lineLimit(isLarge ? 3 : 1)
            .padding(.top, Spacing.xs)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private func byline(lead: String?, _ people: [ContributorNavRef]) -> some View {
        if !people.isEmpty {
            FlowLayout(spacing: 4) {
                if let lead {
                    Text(lead)
                        .font(.subheadline)
                        .foregroundStyle(Color.luOnWashSecondary)
                        .accessibilityHidden(true)
                }
                if let folded = Self.folded(people) {
                    Menu {
                        // HIG, Menus: a section title names the group; a second line in an item is its subtitle.
                        Section(Self.menuHeader(isAuthors: lead == nil)) {
                            ForEach(Self.menuRows(people)) { row in
                                Button { onViewContributor(row.id) } label: {
                                    Text(row.name)
                                    if let books = row.books { Text(books) }
                                }
                            }
                        }
                    } label: {
                        link(folded, isAuthor: lead == nil)
                    }
                    .accessibilityLabel(spoken(lead, folded))
                } else {
                    ForEach(Array(people.enumerated()), id: \.element.id) { offset, person in
                        Button { onViewContributor(person.id) } label: {
                            link(person.name + Self.separator(after: offset, of: people.count), isAuthor: lead == nil)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(spoken(lead, person.name))
                        .accessibilityAddTraits(.isLink)
                    }
                }
            }
        }
    }

    /// "Michael Kramer &", "Kate Reading": commas between names, an ampersand before the last.
    static func separator(after offset: Int, of count: Int) -> String {
        if offset == count - 2 { return " &" }
        return offset < count - 2 ? "," : ""
    }

    private func spoken(_ lead: String?, _ name: String) -> String {
        lead.map { "\($0) \(name)" } ?? name
    }

    /// The author reads at body size in the label colour, the narrators a step down in the wash's secondary ink (the
    /// board; 4.5:1 on any wash); both are
    /// buttons to VoiceOver and to the pointer.
    private func link(_ text: String, isAuthor: Bool) -> some View {
        Text(text)
            .font(isAuthor ? .body : .subheadline)
            .foregroundStyle(isAuthor ? Color.primary : Color.luOnWashSecondary)
            // A 44pt hit area around a text-sized link, without spacing the byline out (HIG, Accessibility).
            .minimumTapTarget(visualSize: 20)
    }
}

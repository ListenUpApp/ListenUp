import SwiftUI

/// One person in the byline's folded menu: their name and their books in your library (spec §5.5.5, PK1).
struct BylineMenuRow: Equatable, Identifiable {
    let id: String
    let name: String
    /// "14 books"; nil when the library has none counted, so a zero never shows.
    let books: String?
}

/// Now Playing's title block (player spec §5.2, iOS; the `NP-iPhone` and `NP-iPad` boards). On iPhone (`.phone`) three
/// centred lines: the title, the chapter's own title and who narrates it. On iPad's wide player the series as a link
/// above the title (P10), then the book's title, the author and "Read by" line whose names are links, then the chapter
/// line. More than three people in a role fold to "Michael Kramer & 3 more", a pull-down menu of the names (HIG,
/// Pull-down buttons: a menu for a navigation choice). Titles wrap rather than truncate as text grows (HIG,
/// Typography: "aim to display as much useful text at the largest accessibility font size as you do at the largest
/// standard size").
struct NowPlayingTitleBlock: View {
    let observer: PlayerObserver
    /// Shows "The Stormlight Archive · Book 1" above the title, as a link: iPad's wide player, where More leaves Go to
    /// Series out (P10). The phone keeps Go to Series in More instead.
    var showsSeries = false
    /// Dismiss Now Playing and open the series.
    var onViewSeries: (String) -> Void = { _ in }
    /// Dismiss Now Playing and open the contributor.
    let onViewContributor: (String) -> Void
    /// How much of the block shows.
    var form: Form = .full

    /// How much of the title block shows.
    enum Form {
        /// iPad's wide player: the series, title, bylines and chapter line, leading.
        case full
        /// The iPhone's three lines, centred on the cover (Simon, 2026-10-27): the bold title, the chapter's own title,
        /// and the mic with "Narrated by …". The authors are in More.
        case phone
        /// One line each of title and chapter, centred, no bylines: read at a distance on a tabletop fold's standing
        /// half, as Android's tabletop player does.
        case oneLine
    }

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

    /// The phone's chapter line: the chapter's own title, with no counter; "Chapter 6" untitled; "Whole Book" chapterless.
    static func phoneChapterLine(number: Int, title: String, isWholeBook: Bool) -> String {
        chapterLine(number: number, total: number, title: title, showsCounter: false, isWholeBook: isWholeBook)
    }

    /// "The Stormlight Archive · Book 1", or the series alone when the book has no place in it.
    static func seriesLine(_ series: SeriesNavRef) -> String {
        guard let sequence = series.sequenceLabel, !sequence.isEmpty else { return series.name }
        return String(format: String(localized: "player.np_series_book"), series.name, sequence)
    }

    /// "Narrator 1 & 4 more" for more than three people, else nil (the names are listed).
    static func folded(_ people: [ContributorNavRef]) -> String? {
        guard people.count > foldThreshold, let lead = people.first else { return nil }
        return String(format: String(localized: "player.np_byline_fold"), lead.name, people.count - 1)
    }

    /// The folded menu's rows: each name with its book count as the menu item's subtitle.
    nonisolated static func menuRows(_ people: [ContributorNavRef]) -> [BylineMenuRow] {
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
    nonisolated static func menuHeader(isAuthors: Bool) -> String {
        isAuthors ? String(localized: "player.sheet_authors") : String(localized: "player.sheet_narrators")
    }

    var body: some View {
        if form == .phone { phoneBlock } else { block }
    }

    /// The iPhone's block: three centred lines, the title the screen's heading.
    private var phoneBlock: some View {
        VStack(alignment: .center, spacing: 6) {
            Text(observer.bookTitle)
                .font(.title2.bold())
                .foregroundStyle(.primary)
                .multilineTextAlignment(.center)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 2)
                .accessibilityAddTraits(.isHeader)
            Text(Self.phoneChapterLine(
                number: observer.chapterIndex + 1,
                title: observer.chapterTitle ?? "",
                isWholeBook: observer.isWholeBook
            ))
            .font(.subheadline)
            .foregroundStyle(Color.luOnWashSecondary)
            .multilineTextAlignment(.center)
            .lineLimit(1)
            narratedBy(observer.narrators)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// The mic and "Narrated by", then each narrator a link; more than three fold to a menu of them. One centred flow:
    /// the names wrap between names when the text is large, each whole, and a name wider than the line wraps within it.
    @ViewBuilder
    private func narratedBy(_ people: [ContributorNavRef]) -> some View {
        if !people.isEmpty {
            FlowLayout(spacing: 4, alignment: .center) {
                Label(String(localized: "book.detail_narrated_by"), systemImage: "music.mic")
                    .font(.subheadline)
                    .foregroundStyle(Color.luOnWashSecondary)
                    .labelStyle(.titleAndIcon)
                if let folded = Self.folded(people) {
                    Menu {
                        Section(Self.menuHeader(isAuthors: false)) {
                            ForEach(Self.menuRows(people)) { row in
                                Button { onViewContributor(row.id) } label: {
                                    Text(row.name)
                                    if let books = row.books { Text(books) }
                                }
                            }
                        }
                    } label: {
                        link(folded, isAuthor: false)
                    }
                } else {
                    ForEach(Array(people.enumerated()), id: \.element.id) { offset, person in
                        Button { onViewContributor(person.id) } label: {
                            link(person.name + Self.separator(after: offset, of: people.count), isAuthor: false)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(person.name)
                        .accessibilityAddTraits(.isLink)
                    }
                }
            }
        }
    }

    /// iPad's block (`.full`) and the tabletop and PDF pane's (`.oneLine`).
    @ViewBuilder
    private var block: some View {
        let isLarge = dynamicTypeSize.isAccessibilitySize
        let isOneLine = form == .oneLine
        VStack(alignment: isOneLine ? .center : .leading, spacing: 3) {
            if showsSeries, !isOneLine, let series = observer.series.first {
                seriesLink(series)
            }
            Text(observer.bookTitle)
                .font(.title2.bold())
                .foregroundStyle(.primary)
                .lineLimit(isOneLine ? 1 : (isLarge ? 3 : 2))
                .accessibilityAddTraits(.isHeader)

            if !isOneLine {
                byline(lead: nil, observer.authors)
                byline(lead: String(localized: "player.np_read_by"), observer.narrators)
            }

            Text(Self.chapterLine(
                number: observer.chapterIndex + 1,
                total: observer.totalChapters,
                title: observer.chapterTitle ?? "",
                showsCounter: observer.showsChapterCounter,
                isWholeBook: observer.isWholeBook
            ))
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.primary)
            .lineLimit(isLarge && !isOneLine ? 3 : 1)
            .padding(.top, Spacing.xs)
        }
        .frame(maxWidth: .infinity, alignment: isOneLine ? .center : .leading)
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

    /// The series, a link in the wash's secondary ink (the `NP-iPad` board), in a full 44pt target (HIG, Accessibility).
    private func seriesLink(_ series: SeriesNavRef) -> some View {
        Button { onViewSeries(series.id) } label: {
            Text(Self.seriesLine(series))
                .font(.subheadline)
                .foregroundStyle(Color.luOnWashSecondary)
                .multilineTextAlignment(.leading)
                .frame(minHeight: TapTarget.minimum, alignment: .leading)
                .contentShape(Rectangle())
                .hoverEffect(.highlight)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(.isLink)
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

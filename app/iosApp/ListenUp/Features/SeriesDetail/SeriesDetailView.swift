import SwiftUI
import Shared

/// Series detail screen — clean-coral design.
///
/// Layout (iPhone, scrolling):
/// 1. Hero — `CoverStack` + "SERIES" eyebrow + title + author + narrator
/// 2. `StatStrip` — Books / Finished / Total
/// 3. Full-width Continue CTA
/// 4. Optional expandable description
/// 5. On a series with sub-series (or for an editor): the "Sub-series" section of cards
/// 6. "Books in Series" header + order toggle, or — on a parent series — the books grouped under
///    each sub-series, then "Also in {series}"
/// 7. The books, as rows of a system inset-grouped `List`
///
/// A child series replaces the "SERIES" eyebrow with its breadcrumb ("Cosmere › Mistborn").
///
/// On iPhone the whole screen is that one `List` — the hero and meta on the plain background above
/// the Books section. When the width allows (`DetailColumns`), hero + meta move into a left rail
/// sized from the width, beside the book `List` on the right.
struct SeriesDetailView: View {
    let seriesId: String

    @Environment(\.dependencies) private var deps
    @State private var observer: SeriesDetailObserver?
    @State private var reversed: Bool = false
    @State private var showEdit: Bool = false
    @State private var showAuthors: Bool = false
    /// Set when an edit-sheet merge soft-deletes the series we are showing; from then on this screen
    /// shows the survivor instead.
    @State private var mergedIntoSeriesId: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The series actually on screen — the survivor after a merge, otherwise the one we were opened
    /// with. Re-targeting in place is deliberate: pushing the survivor would leave the deleted
    /// series in the back stack for the reader to return to, and popping to the library would lose
    /// their place. `.task(id:)` reloads when this changes.
    private var activeSeriesId: String { mergedIntoSeriesId ?? seriesId }

    /// Up to this many author names render inline as tappable chips; beyond it the hero collapses to
    /// "{first} & N others" that opens the authors sheet. Matches Book Detail's inline limit.
    private let inlineAuthorLimit = 2

    var body: some View {
        Group {
            if let observer, !observer.isLoading {
                if let errorMessage = observer.error {
                    errorView(message: errorMessage)
                } else {
                    content(observer: observer)
                }
            } else {
                loadingView
            }
        }
        .background(Color.luSurface)
        .navigationTitle(observer?.seriesName ?? String(localized: "common.series"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Edit is the menu's only action, so a reader without Edit metadata gets no menu at all.
            if observer?.editAccess.offersEditing == true {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            showEdit = true
                        } label: {
                            Label(String(localized: "common.edit"), systemImage: "pencil")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .sheet(isPresented: $showEdit) {
            SeriesEditView(
                seriesId: activeSeriesId,
                onMergedInto: { survivor in mergedIntoSeriesId = survivor }
            )
        }
        .sheet(isPresented: $showAuthors) {
            SeriesAuthorsSheet(
                authors: observer?.seriesAuthors ?? [],
                onClose: { showAuthors = false }
            )
        }
        .sheet(
            isPresented: Binding(
                get: { observer?.addSubSeries.isVisible ?? false },
                set: { if !$0 { observer?.send(.dismissed) } }
            )
        ) {
            if let observer {
                AddSubSeriesSheet(model: observer.addSubSeries, send: observer.send)
            }
        }
        .task(id: activeSeriesId) {
            let vm = deps.createSeriesDetailViewModel()
            let obs = SeriesDetailObserver(viewModel: vm, playerCoordinator: deps.playerCoordinator)
            observer = obs
            obs.loadSeries(seriesId: activeSeriesId)
        }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscriptions.
            observer = nil
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(observer: SeriesDetailObserver) -> some View {
        DetailColumnsReader { columns in
            switch columns {
            case .split(let railWidth): iPadLayout(observer: observer, railWidth: railWidth)
            case .stacked: iPhoneLayout(observer: observer)
            }
        }
    }

    private func iPhoneLayout(observer: SeriesDetailObserver) -> some View {
        List {
            Section {
                VStack(spacing: 0) {
                    heroSection(observer: observer)
                        .padding(.top, Spacing.m)
                    statStripSection(observer: observer)
                        .padding(.vertical, Spacing.l)
                    if let description = observer.seriesDescription, !description.isEmpty {
                        ExpandableText(
                            title: String(localized: "common.about"),
                            text: description,
                            lineLimit: 3
                        )
                        .padding(.horizontal)
                    }
                    continueButton(observer: observer)
                        .padding(.horizontal)
                        .padding(.top, Spacing.l)
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            listSections(observer: observer)
        }
        .listStyle(.insetGrouped)
        .animation(reduceMotion ? nil : .default, value: observer.bookGroups)
    }

    private func iPadLayout(observer: SeriesDetailObserver, railWidth: CGFloat) -> some View {
        HStack(alignment: .top, spacing: DetailColumns.gutter) {
            // Left column — hero + stats + CTA, scrolling on its own when it outgrows the window
            ScrollView {
                VStack(spacing: 0) {
                    heroSection(observer: observer)
                    statStripSection(observer: observer)
                        .padding(.vertical, Spacing.l)
                    if let description = observer.seriesDescription, !description.isEmpty {
                        ExpandableText(
                            title: String(localized: "common.about"),
                            text: description,
                            lineLimit: 3
                        )
                    }
                    continueButton(observer: observer)
                        .padding(.top, Spacing.l)
                }
                .padding(.vertical, Spacing.xl)
            }
            .frame(width: railWidth)
            // Right column — the books list
            List {
                listSections(observer: observer)
            }
            .listStyle(.insetGrouped)
            .animation(reduceMotion ? nil : .default, value: observer.bookGroups)
            .contentMargins(.horizontal, 0, for: .scrollContent)
        }
        .padding(.horizontal, DetailColumns.margin)
    }

    // MARK: - Hero

    private func heroSection(observer: SeriesDetailObserver) -> some View {
        VStack(spacing: 8) {
            CoverStack(covers: observer.books.map(CoverArt.init(book:)), size: 150, peek: 34)
                .accessibilityHidden(true)
            if observer.ancestors.isEmpty {
                Text(String(localized: "series.eyebrow"))
                    .font(.caption.weight(.semibold))
                    .kerning(0.6)
                    .textCase(.uppercase)
                    .foregroundStyle(Color.luTint)
            } else {
                // The breadcrumb takes the eyebrow's place on a child series; each crumb pushes that
                // series, so Back still returns here.
                SeriesPathView(parts: SeriesPathModel.breadcrumb(observer.ancestors))
            }
            Text(observer.seriesName)
                .font(.title.bold())
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            authorsLine(observer: observer)
            Text(observer.countLine)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal)
    }

    /// The series authors, mirroring Book Detail: up to `inlineAuthorLimit` tappable name chips, each
    /// linking to its contributor page; beyond that a tappable "{first} & N others" summary that opens
    /// the full authors sheet. Hidden when the series has no authors.
    @ViewBuilder
    private func authorsLine(observer: SeriesDetailObserver) -> some View {
        let authors = observer.seriesAuthors
        if authors.count > inlineAuthorLimit,
           let summary = collapsedContributorSummary(names: authors.map(\.name), limit: inlineAuthorLimit) {
            Button(action: { showAuthors = true }) {
                Text(summary)
                    .font(.callout)
                    .foregroundStyle(Color.luTint)
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text(String(localized: "book.detail_credits_hint")))
        } else if !authors.isEmpty {
            FlowLayout(spacing: 0, alignment: .center) {
                ForEach(Array(authors.enumerated()), id: \.element.id) { index, author in
                    HStack(spacing: 0) {
                        if index > 0 {
                            Text(", ").foregroundStyle(Color.luTint)
                        }
                        NavigationLink(value: ContributorDestination(id: author.id)) {
                            Text(author.name).foregroundStyle(Color.luTint)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .font(.callout)
        }
    }

    // MARK: - Stat strip

    private func statStripSection(observer: SeriesDetailObserver) -> some View {
        StatStrip(stats: [
            .init(value: "\(observer.bookCount)", label: String(localized: "series.stat_books")),
            .init(value: "\(observer.finishedCount)", label: String(localized: "series.stat_finished")),
            .init(value: observer.totalDuration, label: String(localized: "series.stat_total"))
        ])
    }

    // MARK: - Continue CTA

    private func continueButton(observer: SeriesDetailObserver) -> some View {
        VStack(spacing: Spacing.xs) {
            Button(action: { observer.continueSeries() }) {
                ActionLabel(title: observer.continueButtonTitle, systemImage: "play.fill")
            }
            .prominentAction()
            .disabled(observer.books.isEmpty)
            // A grouped page says where the book sits: "Mistborn Era 1 · Book 3".
            if let subtitle = observer.continueSubtitle {
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    // MARK: - List sections

    /// Sub-series (on a parent, or for an editor who may add the first), then the books — grouped
    /// under each sub-series on a parent page, one flat sortable list otherwise.
    @ViewBuilder
    private func listSections(observer: SeriesDetailObserver) -> some View {
        if observer.isGrouped || observer.editAccess.offersEditing {
            subSeriesSection(observer: observer)
        }
        if observer.isGrouped {
            groupedBooksSections(observer: observer)
        } else {
            booksSection(observer: observer)
        }
    }

    private func subSeriesSection(observer: SeriesDetailObserver) -> some View {
        Section {
            ForEach(observer.childSeries) { card in
                ChildSeriesRow(card: card)
            }
            if observer.editAccess.offersEditing {
                // Editors only; needs the server, so it is disabled — not hidden — offline.
                Button { observer.send(.opened) } label: {
                    Label(String(localized: "series.add_subseries"), systemImage: "plus")
                }
                .disabled(!observer.editAccess.canAddSubSeriesNow)
            }
        } header: {
            sectionTitle(String(localized: "series.subseries"), count: observer.childSeries.count)
        }
    }

    @ViewBuilder
    private func groupedBooksSections(observer: SeriesDetailObserver) -> some View {
        Section {
            EmptyView()
        } header: {
            sectionTitle(String(localized: "series.books"), count: observer.bookCount)
        }
        ForEach(observer.bookGroups) { group in
            Section {
                if group.isCollapsed {
                    // A folded group (a finished sub-series starts this way) expands in place.
                    Button(SeriesHierarchyText.showAll(group.bookCount)) { observer.toggleGroup(group.seriesId) }
                } else {
                    ForEach(group.books, id: \.id) { book in
                        bookRow(book, observer: observer)
                    }
                }
            } header: {
                SeriesGroupHeader(group: group) { observer.toggleGroup(group.seriesId) }
            }
        }
    }

    /// A section title — "Sub-series 4", "Books 23" — that the rotor lists as a heading.
    private func sectionTitle(_ title: String, count: Int) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.title2.bold())
                .foregroundStyle(.primary)
                .accessibilityAddTraits(.isHeader)
            if count > 0 {
                Text("(\(count))")
                    .font(.title2)
                    .foregroundStyle(.secondary)
            }
        }
        .textCase(nil)
    }

    // MARK: - Books section

    private func booksSection(observer: SeriesDetailObserver) -> some View {
        let displayedBooks = reversed ? Array(observer.books.reversed()) : observer.books
        return Section {
            ForEach(displayedBooks, id: \.id) { book in
                bookRow(book, observer: observer)
            }
        } header: {
            booksHeader(observer: observer)
                .textCase(nil)
        }
    }

    /// One book: the row opens it, its button plays it. `book.sequence` is its number in the series
    /// it is listed under.
    private func bookRow(_ book: BookRow, observer: SeriesDetailObserver) -> some View {
        NavigationLink(value: BookDestination(id: book.id)) {
            SeriesBookRow(
                book: book,
                sequence: book.sequence,
                progress: observer.progress(for: book.id),
                isFinished: observer.isFinished(book.id),
                isPlaying: observer.isPlaying(book.id),
                onPlayTapped: { observer.playBook(book.id) }
            )
        }
        .bookContextMenu(bookId: book.id, selection: nil) {
            SeriesBookRow(
                book: book,
                sequence: book.sequence,
                progress: observer.progress(for: book.id),
                isFinished: observer.isFinished(book.id),
                isPlaying: observer.isPlaying(book.id),
                onPlayTapped: {}
            )
            .padding(.horizontal, Spacing.m)
            .padding(.vertical, Spacing.s)
        }
    }

    private func booksHeader(observer: SeriesDetailObserver) -> some View {
        HStack {
            Text(String(localized: "series.books_header"))
                .font(.title2.bold())
                .foregroundStyle(.primary)
                .accessibilityAddTraits(.isHeader)
            Text("(\(observer.bookCount))")
                .font(.title2)
                .foregroundStyle(.secondary)
            Spacer()
            Button(action: { reversed.toggle() }) {
                Image(systemName: reversed ? "arrow.up" : "arrow.down")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luTint)
                    .minimumTapTarget(visualSize: 20)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(
                String(localized: reversed ? "series.sort_ascending_a11y" : "series.sort_descending_a11y")
            )
        }
    }

    // MARK: - Error

    private func errorView(message: String) -> some View {
        ContentUnavailableView {
            Label(String(localized: "common.error"), systemImage: "exclamationmark.triangle")
        } description: {
            Text(message)
        }
    }

    // MARK: - Loading

    private var loadingView: some View {
        LoadingStateView()
    }
}

#Preview {
    NavigationStack {
        SeriesDetailView(seriesId: "preview")
    }
}

import SwiftUI
import Shared

/// Series detail screen — clean-coral design.
///
/// Layout (iPhone, scrolling):
/// 1. Hero — `CoverStack` + "SERIES" eyebrow + title + author + narrator
/// 2. `StatStrip` — Books / Finished / Total
/// 3. Full-width Continue CTA
/// 4. Optional expandable description
/// 5. "Books in Series" header + order toggle
/// 6. The books, as rows of a system inset-grouped `List`
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
                        .padding(.top, 16)
                    statStripSection(observer: observer)
                        .padding(.vertical, 20)
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
                        .padding(.top, 20)
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            booksSection(observer: observer)
        }
        .listStyle(.insetGrouped)
    }

    private func iPadLayout(observer: SeriesDetailObserver, railWidth: CGFloat) -> some View {
        HStack(alignment: .top, spacing: DetailColumns.gutter) {
            // Left column — hero + stats + CTA, scrolling on its own when it outgrows the window
            ScrollView {
                VStack(spacing: 0) {
                    heroSection(observer: observer)
                    statStripSection(observer: observer)
                        .padding(.vertical, 20)
                    if let description = observer.seriesDescription, !description.isEmpty {
                        ExpandableText(
                            title: String(localized: "common.about"),
                            text: description,
                            lineLimit: 3
                        )
                    }
                    continueButton(observer: observer)
                        .padding(.top, 20)
                }
                .padding(.vertical, 24)
            }
            .frame(width: railWidth)
            // Right column — the books list
            List {
                booksSection(observer: observer)
            }
            .listStyle(.insetGrouped)
            .contentMargins(.horizontal, 0, for: .scrollContent)
        }
        .padding(.horizontal, DetailColumns.margin)
    }

    // MARK: - Hero

    private func heroSection(observer: SeriesDetailObserver) -> some View {
        VStack(spacing: 8) {
            CoverStack(covers: observer.books.map(CoverArt.init(book:)), size: 150, peek: 34)
                .accessibilityHidden(true)
            Text(String(localized: "series.eyebrow"))
                .font(.caption.weight(.semibold))
                .kerning(0.6)
                .textCase(.uppercase)
                .foregroundStyle(Color.luTint)
            Text(observer.seriesName)
                .font(.title.bold())
                .multilineTextAlignment(.center)
            authorsLine(observer: observer)
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
        Button(action: { observer.continueSeries() }) {
            ActionLabel(title: observer.continueButtonTitle, systemImage: "play.fill")
        }
        .prominentAction()
        .disabled(observer.books.isEmpty)
    }

    // MARK: - Books section

    private func booksSection(observer: SeriesDetailObserver) -> some View {
        let displayedBooks = reversed ? Array(observer.books.reversed()) : observer.books
        return Section {
            ForEach(displayedBooks, id: \.id) { book in
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
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                }
            }
        } header: {
            booksHeader(observer: observer)
                .textCase(nil)
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

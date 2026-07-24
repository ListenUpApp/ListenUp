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
/// 6. `FieldGroup` of `SeriesBookRow` entries
///
/// iPad (`horizontalSizeClass == .regular`) splits hero + meta into a fixed left
/// column beside a right column with the book list.
struct SeriesDetailView: View {
    let seriesId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var observer: SeriesDetailObserver?
    @State private var reversed: Bool = false
    @State private var showEdit: Bool = false
    @State private var showAuthors: Bool = false

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
            SeriesEditView(seriesId: seriesId)
        }
        .sheet(isPresented: $showAuthors) {
            SeriesAuthorsSheet(
                authors: observer?.seriesAuthors ?? [],
                onClose: { showAuthors = false }
            )
        }
        .task(id: seriesId) {
            let vm = deps.createSeriesDetailViewModel()
            let obs = SeriesDetailObserver(viewModel: vm, playerCoordinator: deps.playerCoordinator)
            observer = obs
            obs.loadSeries(seriesId: seriesId)
        }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscriptions.
            observer = nil
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(observer: SeriesDetailObserver) -> some View {
        if hSize == .regular {
            iPadLayout(observer: observer)
        } else {
            iPhoneLayout(observer: observer)
        }
    }

    private func iPhoneLayout(observer: SeriesDetailObserver) -> some View {
        ScrollView {
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
                booksSection(observer: observer)
                    .padding(.top, 20)
            }
            .padding(.bottom, 32)
        }
    }

    private func iPadLayout(observer: SeriesDetailObserver) -> some View {
        ScrollView {
            HStack(alignment: .top, spacing: 40) {
                // Left column — hero + stats + CTA
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
                .frame(width: 340, alignment: .top)
                // Right column — books list
                VStack(spacing: 0) {
                    booksSection(observer: observer)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal)
            .padding(.vertical, 24)
        }
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
        PrimaryButton(title: observer.continueButtonTitle, icon: "play.fill", action: { observer.continueSeries() })
            .disabled(observer.books.isEmpty)
    }

    // MARK: - Books section

    private func booksSection(observer: SeriesDetailObserver) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            booksHeader(observer: observer)
                .padding(.horizontal)
            let displayedBooks = reversed
                ? Array(observer.books.reversed())
                : observer.books
            booksList(books: displayedBooks, observer: observer)
                .padding(.horizontal)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func booksHeader(observer: SeriesDetailObserver) -> some View {
        HStack {
            Text(String(localized: "series.books_header"))
                .font(.title2.bold())
            Text("(\(observer.bookCount))")
                .font(.title2)
                .foregroundStyle(Color.luLabel2)
            Spacer()
            Button(action: { reversed.toggle() }) {
                Image(systemName: reversed ? "arrow.up" : "arrow.down")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luTint)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(reversed ? "Sort ascending" : "Sort descending")
        }
    }

    private func booksList(books: [BookRow], observer: SeriesDetailObserver) -> some View {
        FieldGroup(books, id: \.id, separatorInset: 76) { book in
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
            .buttonStyle(.plain)
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

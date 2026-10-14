import SwiftUI
import Shared

/// Book detail screen — the native, redesigned assembly.
///
/// A centered hero (with a soft `CoverGlow` halo behind the cover) leads on a neutral band, with the Resume unit and
/// the actions (`listenUnit`) inside it, followed by the description / chapters / details sections — with Readers
/// and, for a book matched on Hardcover, its Hardcover section. A narrow width stacks everything; a wide one splits
/// into a left rail (hero + the listen unit), sized from the width, beside a flexible right column (description,
/// chapters, details) — see `DetailColumns`. All state comes from `BookDetailObserver`; the overflow menu offers
/// Mark as Finished, the progress resets and, for an admin, Delete Book.
struct BookDetailView: View {
    let bookId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @State var observer: BookDetailObserver?
    @State private var readersObserver: BookReadersObserver?
    @State private var ratingsObserver: BookRatingsObserver?
    @State var hardcoverObserver: BookHardcoverObserver?
    /// Find on Hardcover, opened from the Hardcover section (Change Match, or Find on Hardcover).
    @State var hardcoverMatchTarget: HardcoverMatchTarget?
    @State var confirmingHardcoverRemoval = false
    /// The confirmation's message while switching Sync with Hardcover off asks first (#1541).
    @State var keepOffMessage: String?
    @State private var showRateSheet = false
    @State private var showRatingBreakdown = false
    /// Counts completed book actions (download, delete download, mark finished) so `commit`
    /// fires once per deliberate action.
    @State var bookActionCount = 0
    /// Mark as Finished, from the overflow menu: asks for the started and finished days first.
    @State var showMarkFinished = false
    /// True once the hero title has scrolled under the navigation bar.
    @State var showsInlineTitle = false

    var body: some View {
        Group {
            if let observer, !observer.isLoading {
                if let error = observer.error {
                    errorView(message: error)
                } else {
                    content(observer)
                }
            } else {
                loadingView
            }
        }
        .background(Color(.systemBackground))
        // The book's own title, not "About": it is the back-stack label and VoiceOver's screen name. The visible
        // inline title stays hidden until the hero title scrolls away, so the title is never on screen twice.
        .navigationTitle(observer?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(observer?.title ?? "")
                    .font(.headline)
                    .lineLimit(1)
                    .opacity(showsInlineTitle ? 1 : 0)
                    .accessibilityHidden(!showsInlineTitle)
            }
            overflowMenu
        }
        .sheet(isPresented: $showMarkFinished) {
            if let observer {
                MarkFinishedSheet(startedAtMs: observer.startedAtMs) { started, finished in
                    bookActionCount += 1
                    observer.markFinished(started: started, finished: finished)
                }
            }
        }
        .sheet(isPresented: shelfPickerBinding) {
            if let observer {
                ShelfPickerSheet(observer: observer) {
                    observer.closeShelfPicker()
                    observer.clearShelfError()
                }
            }
        }
        .sheet(isPresented: collectionPickerBinding) {
            // Collections are admin-managed — guard the sheet content on isAdmin as
            // defense-in-depth, so it can't render for a non-admin even if the
            // presentation state ever leaks true.
            if let observer, observer.isAdmin {
                CollectionPickerSheet(observer: observer) {
                    observer.closeCollectionPicker()
                    observer.clearCollectionError()
                }
            }
        }
        .sheet(isPresented: $showEdit) {
            BookEditView(bookId: bookId)
        }
        // A sheet like every other editor (HIG, Sheets); the editor holds its swipe-down while it
        // has unsaved work, so the draft is never dropped without asking.
        .sheet(isPresented: $showChapterEditor) {
            ChapterEditorView(bookId: bookId)
        }
        // Match details: pushed on a compact width, a full-screen split view on a regular one.
        .bookMatchDetails($matchTarget)
        // The receipt Match details leaves behind, with Undo and See What Changed.
        .matchReceipt(bookId: bookId)
        .lastMatchFeedback(observer)
        .sheet(isPresented: $showRateSheet) {
            if let ratingsObserver, case .ready(let snapshot) = ratingsObserver.phase {
                RateBookSheet(
                    current: snapshot.mine,
                    onSave: { ratingsObserver.rate(halfStars: $0, note: $1) },
                    onClear: { ratingsObserver.clear() },
                    onClose: { showRateSheet = false }
                )
            }
        }
        .sheet(isPresented: $showRatingBreakdown) {
            if let ratingsObserver, case .ready(let snapshot) = ratingsObserver.phase {
                RatingBreakdownSheet(
                    breakdown: snapshot.breakdown,
                    score: snapshot.external,
                    listeners: snapshot.listeners,
                    canRefresh: snapshot.canRefresh,
                    isRefreshingExternal: snapshot.isRefreshingExternal,
                    onRefresh: { ratingsObserver.refreshExternal() },
                    onClose: { showRatingBreakdown = false },
                    nowMs: Int64(Date().timeIntervalSince1970 * 1000)
                )
            }
        }
        .sheet(item: $hardcoverMatchTarget) { HardcoverMatchSheet(bookId: $0.bookId) }
        .sheet(isPresented: $showCast) {
            if let observer, let book = observer.book {
                CastCreditsSheet(book: book) { showCast = false }
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
        .fullScreenCover(item: Binding(
            get: { observer?.documentToOpen },
            set: { if $0 == nil { observer?.dismissReader() } }
        )) { doc in
            DocumentReaderView(document: doc, onDone: { observer?.dismissReader() })
        }
        .alert(
            String(localized: "book.detail_document_viewer_coming_soon"),
            isPresented: Binding(
                get: { observer?.showComingSoon ?? false },
                set: { if !$0 { observer?.dismissComingSoon() } }
            )
        ) {
            Button(String(localized: "common.ok"), role: .cancel) { observer?.dismissComingSoon() }
        }
        // The book was deleted (the observer has already purged this device's copy): leave before
        // the tombstone syncs and the row vanishes underneath the screen.
        .onChange(of: observer?.didDeleteBook ?? false) { _, deleted in
            if deleted { dismiss() }
        }
        .task(id: bookId) {
            guard observer == nil else { return }
            let vm = deps.createBookDetailViewModel()
            let obs = BookDetailObserver(
                viewModel: vm,
                playerObserver: deps.playerObserver,
                downloadService: deps.downloadService
            )
            observer = obs
            obs.loadBook(bookId: bookId)

            // The Readers VM is bookId-parameterized at construction and observes immediately —
            // no separate load call needed.
            readersObserver = BookReadersObserver(viewModel: deps.createBookReadersViewModel(bookId: bookId))
            ratingsObserver = BookRatingsObserver(viewModel: deps.createBookRatingsViewModel(bookId: bookId))
            hardcoverObserver = BookHardcoverObserver(viewModel: deps.createBookHardcoverViewModel(bookId: bookId))
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(_ observer: BookDetailObserver) -> some View {
        DetailColumnsReader { columns in
            ScrollView {
                Group {
                    switch columns {
                    case .split(let railWidth): regularContent(observer, railWidth: railWidth)
                    case .stacked: compactContent(observer)
                    }
                }
                .padding(.bottom, Spacing.xxl)
                // One modifier for every book action, above the layout branch, so one tap fires `.success`
                // once whichever branch renders.
                .haptic(.commit, trigger: bookActionCount)
            }
        }
    }

    /// iPhone: a single vertical stack of every section, the hero and the listen unit on one band.
    @ViewBuilder
    private func compactContent(_ observer: BookDetailObserver) -> some View {
        VStack(spacing: 24) {
            VStack(spacing: 20) {
                hero(observer)

                VStack(spacing: 20) {
                    heldSection(observer)
                    if observer.layout.showsPlayback {
                        serverBanner(observer)
                        listenUnit(observer)
                    }
                }
                .padding(.horizontal)
            }
            .padding(.top, Spacing.xs)
            .padding(.bottom, Spacing.l)
            .background(
                BookDetailHeroTone.color(for: colorScheme),
                in: UnevenRoundedRectangle(bottomLeadingRadius: Radius.xxl, bottomTrailingRadius: Radius.xxl)
            )
            // The band runs up under the navigation bar; held short of the rounded bottom so its corners show.
            .background(
                BookDetailHeroTone.color(for: colorScheme)
                    .ignoresSafeArea(edges: .top)
                    .padding(.bottom, Radius.xxl)
            )

            VStack(spacing: 20) {
                lastMatchSection(observer)

                Divider()

                descriptionAndVisibility(observer)

                contentSections(observer)
            }
            .padding(.horizontal)
        }
    }

    /// Wide: a rail sized from the width beside a flexible right column.
    @ViewBuilder
    private func regularContent(_ observer: BookDetailObserver, railWidth: CGFloat) -> some View {
        HStack(alignment: .top, spacing: DetailColumns.gutter) {
            VStack(spacing: 20) {
                hero(observer)

                heldSection(observer)
                if observer.layout.showsPlayback {
                    serverBanner(observer)
                    listenUnit(observer)
                }
                lastMatchSection(observer)
            }
            .padding(Spacing.l)
            .background(
                BookDetailHeroTone.color(for: colorScheme),
                in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
            )
            .frame(width: railWidth)

            VStack(alignment: .leading, spacing: 28) {
                descriptionAndVisibility(observer)

                contentSections(observer)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, DetailColumns.margin)
        .padding(.top, Spacing.xxl)
    }

    /// The "server unreachable" banner — shown only when the server is genuinely unreachable AND
    /// the book isn't downloaded (`showServerWarning`), explaining why Play/Download are disabled
    /// and offering a Retry. The signal is evidence-based, so the banner clears itself the moment
    /// any traffic reaches the server. Rendered above the listen unit on phone and wide layouts.
    @ViewBuilder
    private func serverBanner(_ observer: BookDetailObserver) -> some View {
        if observer.showServerWarning {
            ServerUnreachableBanner(onRetry: { observer.retryConnection() })
        }
    }

    /// The rating block, above Readers and Chapters. Renders nothing while the ratings are loading, so it
    /// never flashes an invitation at someone who has already rated.
    @ViewBuilder
    var ratingSection: some View {
        if case .ready(let snapshot) = ratingsObserver?.phase {
            Divider()
            BookRatingSection(
                snapshot: snapshot,
                onSetStars: { ratingsObserver?.setStars($0) },
                onEditNote: { showRateSheet = true },
                onRemove: { ratingsObserver?.clear() },
                onOpenSources: { showRatingBreakdown = true },
                onRefreshExternal: { ratingsObserver?.refreshExternal() }
            )
        }
    }

    /// The social "Readers" block. Renders only when the readers VM has data; loading, empty,
    /// and error phases keep the section (and its surrounding divider) out of the layout entirely.
    @ViewBuilder
    var readersSection: some View {
        if case .data(let rows) = readersObserver?.phase {
            Divider()
            BookReadersSection(readers: rows)
        }
    }

    func detailsSection(_ observer: BookDetailObserver) -> some View {
        let audioFormat = observer.audioFormat
        return BookDetailsSection(
            authors: observer.authors,
            narrators: observer.narrators,
            lengthLabel: observer.duration,
            chapterCount: observer.chapters.count,
            publisher: observer.publisher,
            released: observer.year.map(String.init),
            language: observer.language,
            format: audioFormat.format,
            bitrate: audioFormat.bitrate,
            sampleRate: audioFormat.sampleRate,
            channels: audioFormat.channels,
            onOpenCast: { showCast = true }
        )
    }

    @State var showDiscardConfirmation = false
    @State var showRestartConfirmation = false
    @State var showDeleteBookConfirmation = false
    @State var showEdit = false
    @State var showChapterEditor = false
    /// Match details, open for this book. `nil` when closed; Match details clears it after Apply.
    @State var matchTarget: BookMatchTarget?
    @State var showReleaseConfirmation = false
    @State var showCast = false

    // MARK: - Shelf picker presentation

    private var shelfPickerBinding: Binding<Bool> {
        Binding(
            get: { observer?.showShelfPicker ?? false },
            set: { isPresented in
                if !isPresented {
                    observer?.closeShelfPicker()
                    observer?.clearShelfError()
                }
            }
        )
    }

    // MARK: - Collection picker presentation

    private var collectionPickerBinding: Binding<Bool> {
        Binding(
            // Admin-only: the get returns false for non-admins, so the picker can never
            // present even if showCollectionPicker leaks true (defense-in-depth).
            get: { (observer?.isAdmin ?? false) && (observer?.showCollectionPicker ?? false) },
            set: { isPresented in
                if !isPresented {
                    observer?.closeCollectionPicker()
                    observer?.clearCollectionError()
                }
            }
        )
    }

    // MARK: - Error & loading

    private func errorView(message: String) -> some View {
        ContentUnavailableView {
            Label(String(localized: "book.detail_error_title"), systemImage: "exclamationmark.triangle")
        } description: {
            Text(message)
        } actions: {
            Button(action: { observer?.loadBook(bookId: bookId) }) {
                ActionLabel(title: String(localized: "common.retry"), systemImage: "arrow.clockwise")
            }
            .prominentAction()
            .frame(maxWidth: 240)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var loadingView: some View {
        LoadingStateView()
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        BookDetailView(bookId: "preview-book-id")
    }
}

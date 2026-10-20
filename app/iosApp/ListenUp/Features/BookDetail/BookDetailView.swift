import SwiftUI
import Shared

/// Book detail screen — the native, redesigned assembly.
///
/// A centered hero (with a soft `CoverGlow` halo behind the cover) leads on a neutral band, with the Resume unit, the
/// actions (`listenUnit`) and the social line inside it, followed by the sections in the visit's stage order (spec
/// §2.4): chapters first while listening, the synopsis first while deciding, ratings first once finished. A narrow
/// width stacks everything; a wide one splits into a left rail (hero, listen unit, social line), sized from the width,
/// beside a main column of the sections — see `DetailColumns` and `BookDetailIPadRows`. All state comes from
/// `BookDetailObserver`; the More menu (`+OverflowMenu`) is the shared rule's groups, Delete from Server last.
struct BookDetailView: View {
    let bookId: String
    /// Where the screen lands, if not at its top: Home's finishers line opens it on Readers (spec §6.4).
    let focus: BookDetailFocus?

    init(bookId: String, focus: BookDetailFocus? = nil) {
        self.bookId = bookId
        self.focus = focus
    }

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) var dismiss
    @Environment(\.colorScheme) var colorScheme
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    /// The hero's cover colours (spec §2.3): neutral until known, at once on a revisit.
    @State var coverTheme = CoverThemeObserver()
    @State var observer: BookDetailObserver?
    @State var readersObserver: BookReadersObserver?
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
    /// The iPad main column's width, which decides whether Ratings sits beside Chapters (`BookDetailIPadRows`).
    @State var mainColumnWidth: CGFloat = 0
    /// Set once the Readers landing has run, so a return to this screen keeps the reader's own scroll.
    @State private var landedOnReaders = false
    /// VoiceOver's focus, moved to the Readers section when the screen lands there.
    @AccessibilityFocusState private var readersFocused: Bool

    /// Whether the Readers section has something to show, which is when a landing can scroll to it.
    private var readersHaveLoaded: Bool {
        if case .data = readersObserver?.phase { return true }
        return false
    }

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
            if let observer {
                CastCreditsSheet(
                    title: observer.title,
                    authors: observer.heroAuthors,
                    narrators: observer.heroNarrators,
                    all: observer.castMembers
                ) { showCast = false }
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
        // Load once the book (and so its cover) is known; a re-cover while open loads the new colours.
        .onChange(of: coverKey, initial: true) { _, _ in
            guard let header = observer?.header else { return }
            coverTheme.load(bookId: header.coverBookId, coverHash: header.coverHash)
        }
        .animation(CoverThemeMotion.animation(fadesIn: coverTheme.fadesIn, reduceMotion: reduceMotion),
                   value: coverTheme.colors)
    }

    // MARK: - Content

    @ViewBuilder
    private func content(_ observer: BookDetailObserver) -> some View {
        DetailColumnsReader { columns in
            // The reader the social line and Home's finishers line scroll with, to the Readers section's anchor.
            ScrollViewReader { proxy in
                ScrollView {
                    Group {
                        switch columns {
                        case .split(let railWidth): regularContent(observer, railWidth: railWidth, proxy: proxy)
                        case .stacked: compactContent(observer, proxy: proxy)
                        }
                    }
                    .padding(.bottom, Spacing.xxl)
                    // One modifier for every book action, above the layout branch, so one tap fires `.success`
                    // once whichever branch renders.
                    .haptic(.commit, trigger: bookActionCount)
                }
                // Home's finishers line (spec §6.4): once Readers has something to show, bring it to the top and
                // VoiceOver's focus to it — once per visit.
                .onChange(of: readersHaveLoaded, initial: true) { _, loaded in
                    guard loaded, focus == .readers, !landedOnReaders else { return }
                    landedOnReaders = true
                    withAnimation { proxy.scrollTo(Self.readersAnchor, anchor: .top) }
                    readersFocused = true
                }
            }
        }
    }

    /// iPhone: the hero, the listen unit and the social line on one band, then every section in the visit's order.
    @ViewBuilder
    private func compactContent(_ observer: BookDetailObserver, proxy: ScrollViewProxy) -> some View {
        VStack(spacing: 24) {
            VStack(spacing: 20) {
                scanWarning(observer)
                    .padding(.horizontal)
                hero(observer)

                VStack(spacing: 20) {
                    heldSection(observer)
                    if observer.layout.showsPlayback {
                        listenUnit(observer)
                    }
                    socialLine(observer, proxy: proxy)
                }
                .padding(.horizontal)
            }
            .padding(.top, Spacing.xs)
            .padding(.bottom, Spacing.l)
            .background(
                heroWash,
                in: UnevenRoundedRectangle(bottomLeadingRadius: Radius.xxl, bottomTrailingRadius: Radius.xxl)
            )
            // The band runs up under the navigation bar; held short of the rounded bottom so its corners show.
            .background(
                heroWash
                    .ignoresSafeArea(edges: .top)
                    .padding(.bottom, Radius.xxl)
            )

            VStack(spacing: 26) {
                // Admins only, directly under the action unit (spec §3.2.4 rule 4).
                visibilitySection(observer)
                lastMatchSection(observer)
                ForEach(shownSections(observer), id: \.self) { kind in
                    section(kind, observer, isWide: false)
                }
            }
            .padding(.horizontal)
        }
    }

    /// Wide: a rail sized from the width beside a flexible main column of the sections.
    @ViewBuilder
    private func regularContent(
        _ observer: BookDetailObserver,
        railWidth: CGFloat,
        proxy: ScrollViewProxy
    ) -> some View {
        HStack(alignment: .top, spacing: DetailColumns.gutter) {
            VStack(spacing: 20) {
                scanWarning(observer)
                hero(observer)

                heldSection(observer)
                if observer.layout.showsPlayback {
                    listenUnit(observer)
                }
                socialLine(observer, proxy: proxy)
                lastMatchSection(observer)
            }
            .padding(Spacing.l)
            .background(
                heroWash,
                in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
            )
            .frame(width: railWidth)

            VStack(alignment: .leading, spacing: 26) {
                // Admins only, at the top of the main column (spec §3.2.4 rule 4).
                visibilitySection(observer)
                iPadRows(observer)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { mainColumnWidth = $0 }
        }
        .padding(.horizontal, DetailColumns.margin)
        .padding(.top, Spacing.xxl)
    }

    /// The rating block, above Readers and Chapters. Renders nothing while the ratings are loading, so it
    /// never flashes an invitation at someone who has already rated.
    @ViewBuilder
    var ratingSection: some View {
        if case .ready(let snapshot) = ratingsObserver?.phase {
            BookRatingSection(
                snapshot: snapshot,
                onSetStars: { ratingsObserver?.setStars($0) },
                onEditNote: { showRateSheet = true },
                onRemove: { ratingsObserver?.clear() },
                onOpenSources: { showRatingBreakdown = true },
                onRefreshExternal: { ratingsObserver?.refreshExternal() },
                isFinished: observer?.reading.stage == .finished
            )
        }
    }

    /// The social "Readers" block: three rows, then "See All N Readers". Renders only when the readers VM has data;
    /// loading, empty and error phases keep the section out of the layout entirely. Its anchor is what the social
    /// line scrolls to.
    @ViewBuilder
    var readersSection: some View {
        if case .data(let rows) = readersObserver?.phase {
            BookReadersSection(readers: rows, cap: 3, bookId: bookId)
                .id(Self.readersAnchor)
                .accessibilityFocused($readersFocused)
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

    /// The More menu's open question (mark not started, restart, delete from server), if any.
    @State var menuConfirmation: BookDetailMenuConfirmation?
    @State var showEdit = false
    @State var showChapterEditor = false
    /// Match details, open for this book. `nil` when closed; Match details clears it after Apply.
    @State var matchTarget: BookMatchTarget?
    @State var showReleaseConfirmation = false
    @State var showCast = false
    /// Files & Audio or Maps & Illustrations, open from the Details rows or the scan warning's Review.
    @State var detailsSheet: BookDetailsSheet?

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

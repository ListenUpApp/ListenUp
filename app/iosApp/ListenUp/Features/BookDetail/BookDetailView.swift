import SwiftUI
import Shared

/// Book detail screen — the native, redesigned assembly.
///
/// A centered hero (with a soft `CoverGlow` halo behind the cover) leads, followed by
/// a resume bar, two secondary action pills, and the description / chapters /
/// details sections. A narrow width stacks everything; a wide one splits into a left rail
/// (hero + resume + pills), sized from the width, beside a flexible right column (description,
/// chapters, details) — see `DetailColumns`. All state comes from `BookDetailObserver`; the overflow menu offers
/// the progress resets and, for an admin, Delete Book.
struct BookDetailView: View {
    let bookId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State var observer: BookDetailObserver?
    @State private var readersObserver: BookReadersObserver?
    @State private var ratingsObserver: BookRatingsObserver?
    @State private var showRateSheet = false
    @State private var showRatingBreakdown = false
    /// Counts completed book actions (download, delete download, mark finished) so `commit`
    /// fires once per deliberate action.
    @State private var bookActionCount = 0

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
        .navigationTitle(String(localized: "common.about"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { overflowMenu }
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
        .sheet(isPresented: $showMetadataMatch) {
            if let observer {
                MetadataMatchView(
                    bookId: bookId,
                    title: observer.title,
                    author: observer.heroAuthors.first?.name ?? "",
                    asin: observer.asin
                )
            }
        }
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
                    onClose: { showRatingBreakdown = false }
                )
            }
        }
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
                playerCoordinator: deps.playerCoordinator,
                downloadService: deps.downloadService
            )
            observer = obs
            obs.loadBook(bookId: bookId)

            // The Readers VM is bookId-parameterized at construction and observes immediately —
            // no separate load call needed.
            readersObserver = BookReadersObserver(viewModel: deps.createBookReadersViewModel(bookId: bookId))
            ratingsObserver = BookRatingsObserver(viewModel: deps.createBookRatingsViewModel(bookId: bookId))
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
                // One modifier for every book action, above the layout branch. `resumeBar` and
                // `actionPills` are siblings in whichever branch renders, so a modifier on each
                // would put two in the hierarchy at once and fire `.success` twice per tap.
                .haptic(.commit, trigger: bookActionCount)
            }
        }
    }

    /// iPhone: a single vertical stack of every section.
    @ViewBuilder
    private func compactContent(_ observer: BookDetailObserver) -> some View {
        VStack(spacing: 24) {
            BookDetailHero(
                header: observer.header,
                title: observer.title,
                subtitle: observer.subtitle,
                series: observer.series,
                authors: observer.heroAuthors,
                author: observer.authors,
                narrators: observer.heroNarrators,
                narratorsText: observer.narrators,
                chapterCount: observer.chapters.count,
                duration: observer.duration,
                year: observer.year,
                onOpenCast: { showCast = true }
            )

            VStack(spacing: 20) {
                serverBanner(observer)
                resumeBar(observer)
                actionPills(observer)

                Divider()

                BookDescriptionSection(
                    description: observer.bookDescription,
                    genres: observer.genres,
                    tags: observer.tags,
                    moods: observer.moods
                )

                Divider()

                BookChaptersSection(chapters: observer.chapters)

                ratingSection

                readersSection

                Divider()

                if !observer.documents.isEmpty {
                    SupplementaryMaterialsSection(
                        documents: observer.documents,
                        openingDocIds: observer.openingDocIds,
                        onOpen: { observer.openDocument(docId: $0) }
                    )

                    Divider()
                }

                detailsSection(observer)
            }
            .padding(.horizontal)
        }
        .padding(.top, Spacing.xs)
    }

    /// Wide: a rail sized from the width beside a flexible right column.
    @ViewBuilder
    private func regularContent(_ observer: BookDetailObserver, railWidth: CGFloat) -> some View {
        HStack(alignment: .top, spacing: DetailColumns.gutter) {
            VStack(spacing: 20) {
                BookDetailHero(
                    header: observer.header,
                    title: observer.title,
                    subtitle: observer.subtitle,
                    series: observer.series,
                    authors: observer.heroAuthors,
                    author: observer.authors,
                    narrators: observer.heroNarrators,
                    narratorsText: observer.narrators,
                    chapterCount: observer.chapters.count,
                    duration: observer.duration,
                    year: observer.year,
                    onOpenCast: { showCast = true }
                )

                serverBanner(observer)
                resumeBar(observer)
                actionPills(observer)
            }
            .frame(width: railWidth)

            VStack(alignment: .leading, spacing: 28) {
                BookDescriptionSection(
                    description: observer.bookDescription,
                    genres: observer.genres,
                    tags: observer.tags,
                    moods: observer.moods
                )

                Divider()

                BookChaptersSection(chapters: observer.chapters)

                ratingSection

                readersSection

                Divider()

                if !observer.documents.isEmpty {
                    SupplementaryMaterialsSection(
                        documents: observer.documents,
                        openingDocIds: observer.openingDocIds,
                        onOpen: { observer.openDocument(docId: $0) }
                    )

                    Divider()
                }

                detailsSection(observer)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, DetailColumns.margin)
        .padding(.top, Spacing.xxl)
    }

    private func resumeBar(_ observer: BookDetailObserver) -> some View {
        ResumeBar(
            progress: observer.progress,
            isComplete: observer.isComplete,
            timeRemaining: observer.timeRemaining,
            currentChapterLabel: currentChapterLabel(observer),
            downloadState: observer.downloadState,
            downloadProgress: observer.downloadProgress,
            canPlay: observer.canPlay,
            canDownload: observer.canDownload,
            isPlayPending: observer.isPlayPending,
            onResume: { observer.play() },
            onDownload: {
                bookActionCount += 1
                observer.downloadBook()
            },
            onCancelDownload: { observer.cancelDownload() },
            onDeleteDownload: {
                bookActionCount += 1
                observer.deleteDownload()
            }
        )
    }

    /// The "server unreachable" banner — shown only when the server is genuinely unreachable AND
    /// the book isn't downloaded (`showServerWarning`), explaining why Play/Download are disabled
    /// and offering a Retry. The signal is evidence-based, so the banner clears itself the moment
    /// any traffic reaches the server. Rendered above the resume bar on phone and wide layouts.
    @ViewBuilder
    private func serverBanner(_ observer: BookDetailObserver) -> some View {
        if observer.showServerWarning {
            ServerUnreachableBanner(onRetry: { observer.retryConnection() })
        }
    }

    private func actionPills(_ observer: BookDetailObserver) -> some View {
        BookActionPills(
            isComplete: observer.isComplete,
            isMarkingComplete: observer.isMarkingComplete,
            onAddToShelf: { observer.openShelfPicker() },
            onMarkFinished: {
                bookActionCount += 1
                observer.markFinished()
            }
        )
    }

    /// The rating block, directly above Readers. Renders nothing while the ratings are loading, so
    /// it never flashes "Rate" at someone who already has.
    @ViewBuilder
    private var ratingSection: some View {
        if case .ready(let snapshot) = ratingsObserver?.phase {
            Divider()
            BookRatingSection(
                snapshot: snapshot,
                onOpenSheet: { showRateSheet = true },
                onOpenBreakdown: { showRatingBreakdown = true },
                onRefreshExternal: { ratingsObserver?.refreshExternal() }
            )
        }
    }

    /// The social "Readers" block. Renders only when the readers VM has data; loading, empty,
    /// and error phases keep the section (and its surrounding divider) out of the layout entirely.
    @ViewBuilder
    private var readersSection: some View {
        if case .data(let rows) = readersObserver?.phase {
            Divider()
            BookReadersSection(readers: rows)
        }
    }

    private func detailsSection(_ observer: BookDetailObserver) -> some View {
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

    /// "Ch. N · {title}" for the current chapter, or nil if none is current.
    private func currentChapterLabel(_ observer: BookDetailObserver) -> String? {
        guard let idx = observer.chapters.firstIndex(where: { $0.isCurrent }) else { return nil }
        let chapter = observer.chapters[idx]
        return String(format: String(localized: "book.detail_chapter_label"), idx + 1, chapter.title)
    }

    @State var showDiscardConfirmation = false
    @State var showRestartConfirmation = false
    @State var showDeleteBookConfirmation = false
    @State var showEdit = false
    @State var showChapterEditor = false
    @State var showMetadataMatch = false
    @State private var showCast = false

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

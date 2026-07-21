import SwiftUI
import Shared

/// Book detail screen — the native, redesigned assembly.
///
/// A centered hero (with a soft `CoverGlow` halo behind the cover) leads, followed by
/// a resume bar, two secondary action pills, and the description / chapters /
/// details sections. iPhone stacks everything; iPad splits into a fixed left rail
/// (hero + resume + pills) beside a flexible right column (description, chapters,
/// details). All state comes from `BookDetailObserver`; the overflow menu offers
/// Mark as Not Started.
struct BookDetailView: View {
    let bookId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var observer: BookDetailObserver?
    @State private var readersObserver: BookReadersObserver?

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
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(_ observer: BookDetailObserver) -> some View {
        ScrollView {
            Group {
                if hSize == .regular {
                    regularContent(observer)
                } else {
                    compactContent(observer)
                }
            }
            .padding(.bottom, 32)
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
        .padding(.top, 8)
    }

    /// iPad / regular width: fixed left rail beside a flexible right column.
    @ViewBuilder
    private func regularContent(_ observer: BookDetailObserver) -> some View {
        HStack(alignment: .top, spacing: 44) {
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
            .frame(width: 320)

            VStack(alignment: .leading, spacing: 28) {
                BookDescriptionSection(
                    description: observer.bookDescription,
                    genres: observer.genres,
                    tags: observer.tags,
                    moods: observer.moods
                )

                Divider()

                BookChaptersSection(chapters: observer.chapters)

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
        .padding(.horizontal, 40)
        .padding(.top, 28)
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
            onResume: { observer.play() },
            onDownload: { observer.downloadBook() },
            onCancelDownload: { observer.cancelDownload() },
            onDeleteDownload: { observer.deleteDownload() }
        )
    }

    /// The "server unreachable" banner — shown only when the server can't be reached AND the book
    /// isn't downloaded (`showServerWarning`). A point-of-need hint with a Retry: play/download
    /// stay enabled (attempt-first) — a genuine failure surfaces when the attempt is made.
    /// Rendered above the resume bar on both the phone and wide layouts.
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
            onMarkFinished: { observer.markFinished() }
        )
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

    // MARK: - Overflow menu

    @ToolbarContentBuilder
    private var overflowMenu: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button {
                    showEdit = true
                } label: {
                    Label(String(localized: "book.detail_edit_book"), systemImage: "pencil")
                }

                Button {
                    showMetadataMatch = true
                } label: {
                    Label(String(localized: "metadata.match_on_audible"), systemImage: "sparkles")
                }

                Button {
                    observer?.openShelfPicker()
                } label: {
                    Label(String(localized: "book.detail_add_to_shelf"), systemImage: "text.badge.plus")
                }

                if observer?.isAdmin == true {
                    Button {
                        observer?.openCollectionPicker()
                    } label: {
                        Label(
                            String(localized: "book.detail_add_to_collection"),
                            systemImage: "rectangle.stack.badge.plus"
                        )
                    }
                }

                if let shareURL = observer?.shareURL {
                    ShareLink(
                        item: shareURL,
                        subject: Text(observer?.title ?? ""),
                        message: Text(String(
                            format: String(localized: "common.share_book_text"),
                            observer?.title ?? ""
                        ))
                    ) {
                        Label(String(localized: "common.share"), systemImage: "square.and.arrow.up")
                    }
                }

                if observer?.startedAtMs != nil || observer?.isComplete == true {
                    Button {
                        showRestartConfirmation = true
                    } label: {
                        Label(
                            String(localized: "book.detail_restart"),
                            systemImage: "backward.end"
                        )
                    }

                    Button(role: .destructive) {
                        showDiscardConfirmation = true
                    } label: {
                        Label(
                            String(localized: "book.detail_mark_as_not_started"),
                            systemImage: "arrow.counterclockwise"
                        )
                    }
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .confirmationDialog(
                String(localized: "book.detail_mark_not_started_prompt"),
                isPresented: $showDiscardConfirmation,
                titleVisibility: .visible
            ) {
                Button(String(localized: "book.detail_mark_as_not_started"), role: .destructive) {
                    observer?.discardProgress()
                }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            }
            .confirmationDialog(
                String(localized: "book.detail_restart_prompt"),
                isPresented: $showRestartConfirmation,
                titleVisibility: .visible
            ) {
                Button(String(localized: "book.detail_restart"), role: .destructive) {
                    observer?.restartBook()
                }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            }
        }
    }

    @State private var showDiscardConfirmation = false
    @State private var showRestartConfirmation = false
    @State private var showEdit = false
    @State private var showMetadataMatch = false
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
            PrimaryButton(
                title: String(localized: "common.retry"),
                icon: "arrow.clockwise",
                action: { observer?.loadBook(bookId: bookId) }
            )
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

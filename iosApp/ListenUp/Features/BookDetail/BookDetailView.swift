import SwiftUI
@preconcurrency import Shared

/// Book detail screen — the native, redesigned assembly.
///
/// Wires the per-book accent (`observer.tint`) through a centered hero, a resume
/// bar, two secondary action pills, and the description / chapters / details
/// sections. iPhone stacks everything; iPad splits into a fixed left rail
/// (hero + resume + pills) beside a flexible right column (description, chapters,
/// details). All state comes from `BookDetailObserver`; the overflow menu offers
/// Restart and Discard Progress.
struct BookDetailView: View {
    let bookId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var observer: BookDetailObserver?

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
        .sheet(isPresented: $showEdit) {
            BookEditView(bookId: bookId)
        }
        .sheet(isPresented: $showMetadataMatch) {
            if let observer {
                MetadataMatchView(
                    bookId: bookId,
                    title: observer.title,
                    author: observer.book?.authors.first?.name ?? "",
                    asin: observer.book?.asin
                )
            }
        }
        .sheet(isPresented: $showCast) {
            if let observer, let book = observer.book {
                CastCreditsSheet(book: book, tint: observer.tint) { showCast = false }
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
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.4), value: observer.tint)
        }
    }

    /// iPhone: a single vertical stack of every section.
    @ViewBuilder
    private func compactContent(_ observer: BookDetailObserver) -> some View {
        VStack(spacing: 24) {
            BookDetailHero(
                book: observer.book,
                title: observer.title,
                series: observer.series,
                authors: observer.book?.authors ?? [],
                author: observer.authors,
                narrators: observer.book?.narrators ?? [],
                narratorsText: observer.narrators,
                chapterCount: observer.chapters.count,
                duration: observer.duration,
                year: observer.year,
                tint: observer.tint,
                onOpenCast: { showCast = true }
            )

            VStack(spacing: 20) {
                resumeBar(observer)
                actionPills(observer)

                Divider()

                BookDescriptionSection(
                    description: observer.bookDescription,
                    genres: observer.genres,
                    tags: observer.tags,
                    moods: observer.moods,
                    tint: observer.tint
                )

                Divider()

                BookChaptersSection(chapters: observer.chapters, tint: observer.tint)

                Divider()

                if !observer.documents.isEmpty {
                    SupplementaryMaterialsSection(
                        documents: observer.documents,
                        openingDocIds: observer.openingDocIds,
                        tint: observer.tint,
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
                    book: observer.book,
                    title: observer.title,
                    series: observer.series,
                    authors: observer.book?.authors ?? [],
                    author: observer.authors,
                    narrators: observer.book?.narrators ?? [],
                    narratorsText: observer.narrators,
                    chapterCount: observer.chapters.count,
                    duration: observer.duration,
                    year: observer.year,
                    tint: observer.tint,
                    onOpenCast: { showCast = true }
                )

                resumeBar(observer)
                actionPills(observer)
            }
            .frame(width: 320)

            VStack(alignment: .leading, spacing: 28) {
                BookDescriptionSection(
                    description: observer.bookDescription,
                    genres: observer.genres,
                    tags: observer.tags,
                    moods: observer.moods,
                    tint: observer.tint
                )

                Divider()

                BookChaptersSection(chapters: observer.chapters, tint: observer.tint)

                Divider()

                if !observer.documents.isEmpty {
                    SupplementaryMaterialsSection(
                        documents: observer.documents,
                        openingDocIds: observer.openingDocIds,
                        tint: observer.tint,
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
            tint: observer.tint,
            onResume: { observer.play() },
            onDownload: { observer.downloadBook() },
            onCancelDownload: { observer.cancelDownload() },
            onDeleteDownload: { observer.deleteDownload() }
        )
    }

    private func actionPills(_ observer: BookDetailObserver) -> some View {
        BookActionPills(
            tint: observer.tint,
            isComplete: observer.isComplete,
            isMarkingComplete: observer.isMarkingComplete,
            onAddToShelf: { observer.openShelfPicker() },
            onMarkFinished: { observer.markFinished() }
        )
    }

    private func detailsSection(_ observer: BookDetailObserver) -> some View {
        BookDetailsSection(
            narrators: observer.narrators,
            lengthLabel: observer.duration,
            chapterCount: observer.chapters.count,
            publisher: observer.book?.publisher,
            released: observer.year.map(String.init),
            language: observer.book?.language,
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
                    showRestartConfirmation = true
                } label: {
                    Label(String(localized: "book.detail_restart"), systemImage: "arrow.counterclockwise")
                }

                Button(role: .destructive) {
                    showDiscardConfirmation = true
                } label: {
                    Label(String(localized: "book.detail_discard_progress"), systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .confirmationDialog(
                String(localized: "book.detail_restart_prompt"),
                isPresented: $showRestartConfirmation,
                titleVisibility: .visible
            ) {
                Button(String(localized: "book.detail_restart")) { observer?.restart() }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            }
            .confirmationDialog(
                String(localized: "book.detail_discard_progress_prompt"),
                isPresented: $showDiscardConfirmation,
                titleVisibility: .visible
            ) {
                Button(String(localized: "book.detail_discard_progress"), role: .destructive) {
                    observer?.discardProgress()
                }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            }
        }
    }

    @State private var showRestartConfirmation = false
    @State private var showDiscardConfirmation = false
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

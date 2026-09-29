import SwiftUI
@preconcurrency import Shared

/// A pending multi-contributor picker: the choices and the dialog title.
private struct ContributorPickerRequest: Identifiable {
    let id = UUID()
    let title: String
    let choices: [ContributorNavRef]
}

/// The full-screen audiobook player, presented by `MainTabView` as a `fullScreenCover` that zooms
/// out of the mini player's cover.
///
/// Surface: opaque `systemBackground` under a soft cover-tint wash — the player is content, and
/// HIG, Materials: "Don't use Liquid Glass in the content layer". Glass is kept for the floating
/// header controls. The system presentation supplies what the old overlay hand-rolled: VoiceOver
/// modality, the swipe-down dismiss, and the zoom back into the mini player.
///
/// Layout (`PlayerLayoutMode`, from the space actually available):
/// - stacked: header · cover · titles · scrubber · transport · secondary row
/// - compact height: the cover beside that column (a phone in landscape)
/// - regular: the column beside the always-visible "Up Next" chapter pane (iPad)
/// Every column falls back to scrolling when it cannot fit (small phones, AX text sizes).
///
/// The accent is a legibility-clamped tint derived from the cover (coral until it
/// resolves; coral on any failure — never stranded).
struct FullScreenPlayerView: View {
    let observer: PlayerCoordinator
    /// Dismiss the player and navigate to the current book's detail screen.
    var onViewDetails: () -> Void = {}
    /// Dismiss the player and navigate to the given series.
    var onViewSeries: (String) -> Void = { _ in }
    /// Dismiss the player and navigate to the given contributor (author or narrator).
    var onViewContributor: (String) -> Void = { _ in }

    @State private var showChapterList: Bool = false
    @State private var showBoostPicker: Bool = false
    /// Non-nil while the multi-contributor picker is shown; carries the choices + the title.
    @State private var contributorPicker: ContributorPickerRequest?
    @State private var tint: Color = .listenUpOrange
    /// The global default boost — the boost sheet needs it passed in for its "Use default" row
    /// (`PlayerCoordinator` doesn't retain a reference to `PlaybackPreferences` past init, so the
    /// view reads it directly). `nil` means "not known yet", which is NOT the same as 0 dB: the
    /// sheet hides the row rather than offering to reset a book to a default it hasn't read.
    @State private var defaultBoostDb: Float?

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.dependencies) private var deps

    private var margin: CGFloat { PlayerLayoutMode.horizontalMargin }

    var body: some View {
        GeometryReader { proxy in
            let mode = PlayerLayoutMode.resolve(
                size: proxy.size,
                isAccessibilitySize: dynamicTypeSize.isAccessibilitySize
            )
            layout(mode, in: proxy.size)
        }
        // Only the background runs edge to edge; the controls keep clear of the home indicator.
        .background(tintedBackground)
        // Never stranded: if a load fails while the full player is open, show an inline
        // error + Retry over the (now empty) transport rather than a dead screen.
        .overlay { if observer.isErrored { errorOverlay } }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.4), value: tint)
        .task(id: observer.currentBookId) { resolveTint() }
        // Keyed on the sheet so the default is re-read every time the boost picker opens: a
        // one-shot read races the first open (the row would offer 0 dB) and goes stale the moment
        // Settings changes the default while the player is up.
        .task(id: showBoostPicker) {
            defaultBoostDb = try? await deps.playbackPreferences.getDefaultVolumeBoostDb()
        }
        // VoiceOver's two-finger scrub closes the player like the chevron does.
        .accessibilityAction(.escape) { dismiss() }
        .sheet(isPresented: $showBoostPicker) {
            BoostPickerSheet(
                currentBoostDb: observer.volumeBoostDb,
                defaultBoostDb: defaultBoostDb,
                onBoostSelected: { db in
                    observer.setBoost(db)
                    showBoostPicker = false
                },
                onUseDefault: {
                    if let defaultBoostDb { observer.resetBoost(defaultDb: defaultBoostDb) }
                    showBoostPicker = false
                }
            )
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showChapterList) {
            ChapterListSheet(
                observer: observer,
                onDismiss: { showChapterList = false }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .fullScreenCover(item: Binding(
            get: { observer.documentToOpen },
            set: { observer.documentToOpen = $0 }
        )) { doc in
            DocumentReaderView(document: doc, onDone: { observer.documentToOpen = nil })
        }
    }

    // MARK: - Background

    /// Opaque and cover-tinted: `systemBackground` (light/dark adaptive) under a wash of the cover
    /// accent that fades out by mid-screen.
    private var tintedBackground: some View {
        ZStack {
            Color(.systemBackground)
            LinearGradient(
                colors: [tint.opacity(0.30), tint.opacity(0)],
                startPoint: .top,
                endPoint: .center
            )
        }
        .ignoresSafeArea()
    }

    /// Inline failure surface for the full player — a centered card with the failure message and a
    /// Retry that re-drives the errored book, so a failed load never leaves a dead screen.
    private var errorOverlay: some View {
        ZStack {
            Color(.systemBackground).opacity(0.85).ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.largeTitle)
                    .foregroundStyle(tint)
                    .accessibilityHidden(true)
                Text(observer.errorMessage ?? String(localized: "common.something_went_wrong"))
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.primary)
                Button { observer.togglePlayback() } label: {
                    Text("book.detail_retry")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 28)
                        .frame(minHeight: 44)
                        .background(Capsule().fill(tint))
                }
                .buttonStyle(.plain)
                Button { observer.dismissError() } label: {
                    Text("common.dismiss")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
            }
            .padding(32)
        }
    }

    // MARK: - Layout

    @ViewBuilder
    private func layout(_ mode: PlayerLayoutMode, in size: CGSize) -> some View {
        switch mode {
        case .stacked:
            VStack(spacing: 0) {
                header
                fitting { stackedColumn(coverSide: PlayerLayoutMode.coverSide(in: size, mode: .stacked), showsChapters: true) }
            }
        case .regular:
            // The inline "Up Next" pane replaces the chapter sheet, so the column hides its
            // Chapters control.
            let columnWidth = min(620, size.width - NowPlayingUpNextPanel.width)
            let columnSize = CGSize(width: columnWidth, height: size.height)
            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    header
                    fitting {
                        stackedColumn(
                            coverSide: PlayerLayoutMode.coverSide(in: columnSize, mode: .regular),
                            showsChapters: false
                        )
                    }
                }
                .frame(maxWidth: columnWidth)
                .frame(maxWidth: .infinity)

                NowPlayingUpNextPanel(observer: observer, tint: tint)
            }
        case .compactHeight:
            VStack(spacing: 0) {
                header
                HStack(alignment: .center, spacing: 28) {
                    cover(side: PlayerLayoutMode.coverSide(in: size, mode: .compactHeight))
                    fitting { controlsColumn(showsChapters: true) }
                }
                .padding(.horizontal, margin)
                .padding(.bottom, PlayerLayoutMode.verticalMargin)
            }
        }
    }

    /// The column as it is, when it fits; otherwise the same column in a scroll view — the last
    /// resort for small phones and accessibility text sizes, so nothing is ever clipped.
    private func fitting(@ViewBuilder _ content: () -> some View) -> some View {
        ViewThatFits(in: .vertical) {
            content()
            ScrollView {
                content()
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }

    /// Cover above the controls — phones in portrait, narrow windows, and the iPad column.
    private func stackedColumn(coverSide: CGFloat, showsChapters: Bool) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 12)
            cover(side: coverSide)
            Spacer(minLength: 20)
                .frame(maxHeight: 32)
            controlsColumn(showsChapters: showsChapters)
                .padding(.horizontal, margin)
        }
    }

    /// Titles, scrubber, transport, and the secondary row.
    private func controlsColumn(showsChapters: Bool) -> some View {
        VStack(spacing: 0) {
            titleBlock
            Spacer(minLength: 16)
                .frame(maxHeight: 22)
            // Chapter-scoped progress — its own view so its per-frame position reads don't
            // re-evaluate the rest of the player.
            ChapterScrubberSection(observer: observer, tint: tint)
            Spacer(minLength: 12)
            PlayerTransportControls(observer: observer)
            Spacer(minLength: 20)
            PlayerSecondaryControls(
                observer: observer,
                tint: tint,
                showsChaptersControl: showsChapters,
                onShowChapters: { showChapterList = true },
                onShowBoost: { showBoostPicker = true }
            )
            Spacer(minLength: 8)
        }
    }

    private func cover(side: CGFloat) -> some View {
        BookCoverImage(
            bookId: observer.currentBookId,
            coverPath: observer.coverPath,
            coverHash: observer.coverHash
        )
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: .black.opacity(0.25), radius: 16, x: 0, y: 8)
        .accessibilityHidden(true)
    }

    // MARK: - Header

    private var header: some View {
        HStack {
            Button { dismiss() } label: {
                headerGlyph("chevron.down")
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "player.collapse"))

            Spacer()

            if observer.totalChapters > 0 {
                Text(String(
                    format: String(localized: "player.chapter_of"),
                    "\(observer.chapterIndex + 1)",
                    "\(observer.totalChapters)"
                ))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            }

            Spacer()

            moreMenu
        }
        .padding(.horizontal, 14)
    }

    /// A 36pt glass disc — a floating control, where Liquid Glass belongs — in a 44pt hit area.
    private func headerGlyph(_ systemImage: String) -> some View {
        Image(systemName: systemImage)
            .font(.body.weight(.semibold))
            .foregroundStyle(.primary)
            .frame(width: 36, height: 36)
            .glassControl(in: Circle())
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
    }

    private var moreMenu: some View {
        Menu {
            Button(action: onViewDetails) {
                Label(String(localized: "player.go_to_book"), systemImage: "book")
            }
            if observer.firstPdfDocId != nil {
                Button(action: { observer.openCurrentBookPdf() }) {
                    Label(String(localized: "player.open_pdf"), systemImage: "doc.richtext")
                }
            }
            if let seriesId = observer.seriesId {
                Button(action: { onViewSeries(seriesId) }) {
                    Label(String(localized: "player.go_to_series"), systemImage: "books.vertical")
                }
            }
            contributorButton(
                observer.authors,
                single: "player.go_to_author",
                multiple: "player.go_to_author_multiple",
                systemImage: "person"
            )
            contributorButton(
                observer.narrators,
                single: "player.go_to_narrator",
                multiple: "player.go_to_narrator_multiple",
                systemImage: "mic"
            )
            Divider()
            Button(role: .destructive, action: { Task { await observer.stop() } }) {
                Label(String(localized: "player.close_book"), systemImage: "xmark")
            }
        } label: {
            headerGlyph("ellipsis")
        }
        .accessibilityLabel(String(localized: "player.more_options"))
        .confirmationDialog(
            contributorPicker?.title ?? "",
            isPresented: Binding(get: { contributorPicker != nil }, set: { if !$0 { contributorPicker = nil } }),
            titleVisibility: .visible
        ) {
            ForEach(contributorPicker?.choices ?? []) { choice in
                Button(choice.name) {
                    contributorPicker = nil
                    onViewContributor(choice.id)
                }
            }
        }
    }

    /// A "Go to Author/Narrator" menu button: hidden when there are none, a direct navigation for
    /// one, and a picker for several (matching the Android overflow's single-vs-"…" behaviour).
    @ViewBuilder
    private func contributorButton(
        _ refs: [ContributorNavRef],
        single: String.LocalizationValue,
        multiple: String.LocalizationValue,
        systemImage: String
    ) -> some View {
        if refs.count == 1, let only = refs.first {
            Button(action: { onViewContributor(only.id) }) {
                Label(String(localized: single), systemImage: systemImage)
            }
        } else if refs.count > 1 {
            Button(action: {
                contributorPicker = ContributorPickerRequest(title: String(localized: multiple), choices: refs)
            }) {
                Label(String(localized: multiple), systemImage: systemImage)
            }
        }
    }

    // MARK: - Title block

    /// Titles wrap rather than truncate as text grows — HIG, Typography: "aim to display as much
    /// useful text at the largest accessibility font size as you do at the largest standard size".
    private var titleBlock: some View {
        let isLarge = dynamicTypeSize.isAccessibilitySize
        return VStack(alignment: .leading, spacing: 3) {
            Text(observer.bookTitle)
                .font(.title2.bold())
                .foregroundStyle(.primary)
                .lineLimit(isLarge ? 3 : 2)

            // The chapter's own title; fall back to "Chapter N" only when genuinely untitled.
            // No "Ch. N · " prefix — the title is often itself "Chapter N", producing confusing
            // duplicates like "Ch. 3 · Chapter 1" when front-matter offsets the numbering.
            Text(observer.chapterTitle.flatMap { $0.isEmpty ? nil : $0 }
                ?? String(format: String(localized: "player.chapter_number"), observer.chapterIndex + 1))
                .font(.callout)
                .foregroundStyle(.secondary)
                .lineLimit(isLarge ? 3 : 1)

            if !observer.narratorName.isEmpty {
                Text(String(format: String(localized: "book.detail_narrated_by_value"), observer.narratorName))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(isLarge ? 2 : 1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - Tint

    /// Resolves the cover accent. Coral until extraction lands; coral on any failure
    /// (never stranded). Keyed on the book id so the cache entry is shared with Book
    /// Detail (placeholder `coverPath`s never collide); falls back to `coverPath` only
    /// when the id is unknown.
    private func resolveTint() {
        guard let cacheKey = observer.currentBookId ?? observer.coverPath else { return }
        if let cached = CoverTintExtractor.shared.cached(bookId: cacheKey) {
            tint = cached.color
            return
        }
        Task {
            if let resolved = await CoverTintExtractor.shared.resolve(bookId: cacheKey, coverPath: observer.coverPath) {
                tint = resolved.color
            }
        }
    }
}

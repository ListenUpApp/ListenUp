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
/// - stacked: header · cover · titles · scrubber · transport · volume · secondary row
/// - compact height: the cover beside that column (a phone in landscape)
/// - regular: the column beside the always-visible "Up Next" chapter pane (iPad)
/// The cover takes the height the controls leave; when even the smallest cover would not fit
/// (small phones, AX text sizes) the column scrolls instead of clipping.
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
    /// The controls column's natural height, measured; the cover sizes itself to what is left.
    @State private var controlsHeight: CGFloat = 0

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.dependencies) private var deps

    private var margin: CGFloat { PlayerLayoutMode.horizontalMargin }
    /// The header row: 44pt controls.
    private static let headerHeight: CGFloat = 44

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
        let belowHeader = CGSize(width: size.width, height: max(0, size.height - Self.headerHeight))
        switch mode {
        case .stacked:
            VStack(spacing: 0) {
                header
                stackedColumn(width: size.width, height: belowHeader.height, showsChapters: true)
            }
        case .regular:
            // The inline "Up Next" pane replaces the chapter sheet, so the column hides its
            // Chapters control.
            let columnWidth = min(620, size.width - NowPlayingUpNextPanel.width)
            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    header
                    stackedColumn(width: columnWidth, height: belowHeader.height, showsChapters: false)
                }
                .frame(maxWidth: columnWidth)
                .frame(maxWidth: .infinity)

                NowPlayingUpNextPanel(observer: observer, tint: tint)
            }
        case .compactHeight:
            VStack(spacing: 0) {
                header
                HStack(alignment: .center, spacing: 28) {
                    cover(side: PlayerLayoutMode.compactHeightCoverSide(in: belowHeader))
                    // The volume view stays out of the short layout: the hardware buttons and
                    // Control Center still set volume, and the transport needs the height.
                    let fits = controlsHeight <= belowHeader.height - PlayerLayoutMode.verticalMargin
                    if fits {
                        controlsColumn(showsChapters: true, showsVolume: false)
                    } else {
                        ScrollView {
                            controlsColumn(showsChapters: true, showsVolume: false)
                        }
                        .scrollBounceBehavior(.basedOnSize)
                    }
                }
                .padding(.horizontal, margin)
                .padding(.bottom, PlayerLayoutMode.verticalMargin)
            }
        }
    }

    /// Cover above the controls — phones in portrait, narrow windows, and the iPad column. The
    /// cover takes the height the controls leave over; when even the smallest cover cannot fit
    /// (small phones, accessibility text sizes), the column scrolls instead of clipping.
    @ViewBuilder
    private func stackedColumn(width: CGFloat, height: CGFloat, showsChapters: Bool) -> some View {
        if let side = PlayerLayoutMode.stackedCoverSide(
            columnWidth: width,
            availableHeight: height,
            controlsHeight: controlsHeight
        ) {
            VStack(spacing: 0) {
                Spacer(minLength: 12)
                cover(side: side)
                Spacer(minLength: 20)
                controlsColumn(showsChapters: showsChapters, showsVolume: true)
                    .padding(.horizontal, margin)
            }
        } else {
            ScrollView {
                VStack(spacing: 20) {
                    cover(side: PlayerLayoutMode.scrollingCoverSide(columnWidth: width))
                        .padding(.top, 12)
                    controlsColumn(showsChapters: showsChapters, showsVolume: true)
                        .padding(.horizontal, margin)
                }
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }

    /// Titles, scrubber, transport, volume, and the secondary row at their natural height, which
    /// is measured so the cover can take exactly what is left.
    private func controlsColumn(showsChapters: Bool, showsVolume: Bool) -> some View {
        VStack(spacing: 0) {
            titleBlock
            Spacer().frame(height: 18)
            // Chapter-scoped progress — its own view so its per-frame position reads don't
            // re-evaluate the rest of the player.
            ChapterScrubberSection(observer: observer, tint: tint)
            Spacer().frame(height: 14)
            PlayerTransportControls(observer: observer)
            if showsVolume {
                Spacer().frame(height: 8)
                SystemVolumeSlider()
            }
            Spacer().frame(height: 12)
            PlayerSecondaryControls(
                observer: observer,
                tint: tint,
                showsChaptersControl: showsChapters,
                onShowChapters: { showChapterList = true },
                onShowBoost: { showBoostPicker = true }
            )
            Spacer().frame(height: 8)
        }
        .fixedSize(horizontal: false, vertical: true)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { controlsHeight = $0 }
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
                .multilineTextAlignment(.center)
                .lineLimit(2)
            }

            Spacer()

            moreMenu
        }
        .frame(minHeight: Self.headerHeight)
        .padding(.horizontal, 14)
    }

    /// A 36pt glass disc — a floating control, where Liquid Glass belongs — in a 44pt hit area.
    /// Like a navigation bar's buttons, the glyph keeps its size at large text settings and offers
    /// the large content viewer instead (HIG, Accessibility).
    private func headerGlyph(_ systemImage: String) -> some View {
        Image(systemName: systemImage)
            .font(.system(size: 17, weight: .semibold)) // decorative fixed size
            .foregroundStyle(.primary)
            .frame(width: 36, height: 36)
            .glassControl(in: Circle())
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .accessibilityShowsLargeContentViewer()
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

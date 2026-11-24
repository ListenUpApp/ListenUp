import SwiftUI
@preconcurrency import Shared

/// The full-screen audiobook player, presented by `MainTabView` as a `fullScreenCover` that zooms
/// out of the mini player's cover.
///
/// Surface: the cover's wash (the same `CoverThemeObserver` Book Detail and Home use), or the
/// player's warm neutral until it resolves or when the cover gives none — the player is content, and HIG, Materials:
/// "Don't use Liquid Glass in the content layer". Glass is kept for the floating header controls. The system
/// presentation supplies VoiceOver modality, the swipe-down dismiss, and the zoom back into the mini player.
///
/// Layout (`PlayerLayoutMode`, from the space actually available):
/// - stacked: header · cover · titles · scrubber · transport (or the state card) · volume · settings row
/// - compact height: the cover beside that column (a phone in landscape)
/// - regular (iPad, and Split View or Stage Manager windows wide enough): "Now Playing" in the header, the series as a
///   link above the title, the cover beside the controls where a 240pt cover fits beside them (the `NP-iPad` board) and
///   above them where it doesn't, and the always-visible `NowPlayingChapterPanel` in place of the Chapters button (P22)
/// - tabletop: a phone half open on a table splits at the hinge — header, cover and title on the standing half;
///   scrubber, transport and settings on the lying half (iPhone Duo; Android's `TabletopNowPlaying`)
/// - book: a phone half open like a book puts the column and the chapter panel on facing pages
/// The cover takes the height the controls leave; when even the smallest cover would not fit
/// (small phones, AX text sizes) the column scrolls instead of clipping.
///
/// The header reads Collapse · where the audio is · More. When the book can't play, or has finished, the shared state
/// card takes the transport's place; everything else stays, so the reader's place stays in view.
struct FullScreenPlayerView: View {
    let observer: PlayerObserver
    /// Dismiss the player and navigate to the current book's detail screen.
    var onViewDetails: () -> Void = {}
    /// Dismiss the player and navigate to the given series.
    var onViewSeries: (String) -> Void = { _ in }
    /// Dismiss the player and navigate to the given contributor (author or narrator).
    var onViewContributor: (String) -> Void = { _ in }
    /// Set by the Playback menu's Show Chapters (⇧⌘C); the player shows its chapters and clears it.
    var chapterListRequest: Binding<Bool> = .constant(false)
    /// The PDF the reader cover shows; the window's shell sets it from the player's requests (front window only).
    var readerDocument: Binding<ReaderDocument?> = .constant(nil)

    @State private var showChapterList: Bool = false
    @State private var showBoostPicker: Bool = false
    /// The cover's colours for the wash: neutral until known, at once on a revisit.
    @State private var coverTheme = CoverThemeObserver()
    /// The global default boost — the boost sheet needs it passed in for its "Use default" row
    /// (read from `PlaybackPreferences` each time the sheet opens, so it is never stale). `nil` means "not known yet", which is NOT the same as 0 dB: the
    /// sheet hides the row rather than offering to reset a book to a default it hasn't read.
    @State private var defaultBoostDb: Float?
    /// The controls column's natural height, measured; the cover sizes itself to what is left.
    @State private var controlsHeight: CGFloat = 0
    /// Show Chapters on a wide window moves VoiceOver to the chapter panel, which is already on screen.
    @AccessibilityFocusState private var chapterPanelFocused: Bool

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.dependencies) private var deps

    var margin: CGFloat { PlayerLayoutMode.horizontalMargin }
    /// The header row: 44pt controls.
    static let headerHeight: CGFloat = FullScreenPlayerHeader.height
    /// Between the player and the chapter panel, and the panel's distance from the window's edge (the board's 24).
    static let panelGap: CGFloat = Spacing.xl
    /// The stacked column's widest, beside the panel: wider and the transport spreads apart.
    private static let regularColumnMaximumWidth: CGFloat = 620

    var body: some View {
        GeometryReader { proxy in
            let mode = PlayerLayoutMode.resolve(
                size: proxy.size,
                isAccessibilitySize: dynamicTypeSize.isAccessibilitySize,
                fold: PlayerFold.from(proxy)
            )
            layout(mode, in: proxy.size)
                // The panel takes over from the sheet when a window grows into `.regular` (Split View, Stage Manager).
                .onChange(of: Self.showsChapterPanel(mode)) { _, showsPanel in
                    if showsPanel { showChapterList = false }
                }
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: mode)
                // ⇧⌘C: the panel where the window shows one, the sheet elsewhere — decided by the same resolved mode.
                .onChange(of: chapterListRequest.wrappedValue, initial: true) { _, requested in
                    guard requested else { return }
                    chapterListRequest.wrappedValue = false
                    if Self.showsChapterPanel(mode), !observer.chapterRows.isEmpty {
                        chapterPanelFocused = true
                    } else {
                        showChapterList = true
                    }
                }
        }
        // Only the background runs edge to edge; the controls keep clear of the home indicator. The wash is opaque, so
        // Reduce Transparency changes nothing here.
        .background(Self.wash(coverTheme.colors, scheme: colorScheme).ignoresSafeArea())
        .task(id: "\(observer.currentBookId ?? "")#\(observer.coverHash ?? "")") {
            if let bookId = observer.currentBookId { coverTheme.load(bookId: bookId, coverHash: observer.coverHash) }
        }
        .animation(CoverThemeMotion.animation(fadesIn: coverTheme.fadesIn, reduceMotion: reduceMotion),
                   value: coverTheme.colors)
        // A revoked book closes Now Playing; the mini player then says why.
        .onChange(of: observer.closesNowPlaying) { _, closes in
            if closes { dismiss() }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "player.np_now_playing_ios"))
        .accessibilityAddTraits(.isModal)
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
                // The sheet stays up after a pick, so each level can be heard before settling (PB5).
                onBoostSelected: { observer.setBoost($0) },
                onUseDefault: { if defaultBoostDb != nil { observer.resetBoost() } }
            )
            .playerSheetDetents()
        }
        .reportsPlayerSheet(Self.isCovered(
            showsBoostPicker: showBoostPicker,
            showsChapterList: showChapterList,
            isReadingDocument: readerDocument.wrappedValue != nil
        ))
        .sheet(isPresented: $showChapterList) {
            ChapterListSheet(
                observer: observer,
                onDismiss: { showChapterList = false }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .fullScreenCover(item: readerDocument) { doc in
            DocumentReaderView(document: doc, onDone: { readerDocument.wrappedValue = nil })
        }
    }

    // MARK: - Coverings

    /// Whether something covers the player: one of its sheets, or the PDF reader. Esc closes that first, so the
    /// shell's Close Player waits while it is up.
    static func isCovered(showsBoostPicker: Bool, showsChapterList: Bool, isReadingDocument: Bool) -> Bool {
        showsBoostPicker || showsChapterList || isReadingDocument
    }

    // MARK: - Background

    /// The cover's wash, or the player's neutral when it has none.
    static func wash(_ colors: CoverColors?, scheme: ColorScheme) -> Color {
        colors?.wash ?? PlayerWashTone.color(for: scheme)
    }

    // MARK: - Layout

    @ViewBuilder
    private func layout(_ mode: PlayerLayoutMode, in size: CGSize) -> some View {
        let belowHeader = CGSize(width: size.width, height: max(0, size.height - Self.headerHeight))
        switch mode {
        case .tabletop(let hinge):
            tabletop(hinge: hinge, in: size) { showChapterList = true }
        case .book(let hinge):
            book(hinge: hinge, in: size, chapterPanelFocused: $chapterPanelFocused)
        case .stacked:
            VStack(spacing: 0) {
                header(showsTitle: false)
                stackedColumn(width: size.width, height: belowHeader.height, showsChapters: true)
            }
        case .regular:
            regularLayout(in: size)
        case .compactHeight:
            VStack(spacing: 0) {
                header(showsTitle: false)
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

    /// The `NP-iPad` board: the header over the player area, and the chapter panel the full height beside it. The panel
    /// replaces the chapter sheet, so the column hides its Chapters control; a chapterless book has nothing to pick, so it
    /// gets no panel and no Chapters either, and the player takes the width.
    @ViewBuilder
    private func regularLayout(in size: CGSize) -> some View {
        let showsPanel = !observer.chapterRows.isEmpty
        // The panel, the gap before it and the margin after it.
        let panelSpace = showsPanel ? PlayerLayoutMode.panelWidth + 2 * Self.panelGap : 0
        let playerWidth = max(0, size.width - panelSpace)
        let playerHeight = max(0, size.height - 2 * Spacing.m - Self.headerHeight)
        HStack(alignment: .top, spacing: Self.panelGap) {
            VStack(spacing: 0) {
                header(showsTitle: true)
                playerArea(width: playerWidth, height: playerHeight)
            }
            .frame(width: playerWidth)
            if showsPanel {
                NowPlayingChapterPanel(observer: observer)
                    .frame(width: PlayerLayoutMode.panelWidth)
                    .accessibilityFocused($chapterPanelFocused)
            }
        }
        .padding(.vertical, Spacing.m)
        .padding(.trailing, showsPanel ? Self.panelGap : 0)
    }

    /// The player beside the panel: the cover beside the controls where it can be 240pt or more, else above them
    /// (`PlayerLayoutMode.regularArrangement`). Either way the controls scroll rather than clip.
    @ViewBuilder
    private func playerArea(width: CGFloat, height: CGFloat) -> some View {
        switch PlayerLayoutMode.regularArrangement(
            playerWidth: width, isAccessibilitySize: dynamicTypeSize.isAccessibilitySize
        ) {
        case .coverBeside(let coverSide):
            HStack(alignment: .center, spacing: PlayerLayoutMode.besideGap) {
                cover(side: min(coverSide, height - 2 * PlayerLayoutMode.verticalMargin))
                let fits = controlsHeight <= height - PlayerLayoutMode.verticalMargin
                if fits {
                    controlsColumn(showsChapters: false, showsVolume: true, showsSeries: true)
                } else {
                    ScrollView {
                        controlsColumn(showsChapters: false, showsVolume: true, showsSeries: true)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                }
            }
            .padding(.horizontal, margin)
            .frame(maxHeight: .infinity)
        case .stacked:
            let columnWidth = min(Self.regularColumnMaximumWidth, width)
            stackedColumn(width: columnWidth, height: height, showsChapters: false, showsSeries: true)
                .frame(width: columnWidth)
                .frame(maxWidth: .infinity)
        }
    }

    /// Cover above the controls — phones in portrait, narrow windows, and the iPad column. The
    /// cover takes the height the controls leave over; when even the smallest cover cannot fit
    /// (small phones, accessibility text sizes), the column scrolls instead of clipping.
    ///
    /// One structure for both cases, so the measured controls keep a single identity. Two branches
    /// (a fitted stack, else a scroll view) left the fitted branch's controls starting from the
    /// height the scroll branch measured while the zoom presentation began at a tiny size — about
    /// 530pt instead of 330 on an iPhone 17e — and the cover stayed stuck at ~155pt with empty
    /// bands around it (Pass 7 Simulator matrix).
    @ViewBuilder
    func stackedColumn(
        width: CGFloat, height: CGFloat, showsChapters: Bool, showsSeries: Bool = false
    ) -> some View {
        let fittedSide = PlayerLayoutMode.stackedCoverSide(
            columnWidth: width,
            availableHeight: height,
            controlsHeight: controlsHeight,
            isAccessibilitySize: dynamicTypeSize.isAccessibilitySize
        )
        ScrollView {
            VStack(spacing: 0) {
                Spacer(minLength: fittedSide == nil ? Spacing.s : 12)
                cover(side: fittedSide ?? PlayerLayoutMode.scrollingCoverSide(columnWidth: width))
                Spacer(minLength: 20)
                controlsColumn(showsChapters: showsChapters, showsVolume: true, showsSeries: showsSeries)
                    .padding(.horizontal, margin)
            }
            .frame(minHeight: height)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    /// Titles, scrubber, transport, volume, and the secondary row at their natural height, which
    /// is measured so the cover can take exactly what is left. `showsSeries` puts the series link above the title, where
    /// More leaves Go to Series out (P10).
    func controlsColumn(showsChapters: Bool, showsVolume: Bool, showsSeries: Bool = false) -> some View {
        VStack(spacing: 0) {
            // The phone's centred three lines; iPad's wide player keeps its leading block with the series and bylines.
            NowPlayingTitleBlock(
                observer: observer, showsSeries: showsSeries, onViewSeries: onViewSeries,
                onViewContributor: onViewContributor, form: showsSeries ? .full : .phone)
            Spacer().frame(height: 18)
            // Chapter-scoped progress — its own view so its per-frame position reads don't
            // re-evaluate the rest of the player. Still under a problem card, live on a finished book (a seek there is
            // Listen Again from that point).
            ChapterScrubberSection(observer: observer)
                .disabled(isShowingProblem)
            Spacer().frame(height: 14)
            if let card = observer.card {
                PlayerStateCardView(
                    card: card,
                    onAction: { observer.onCardAction($0) },
                    onRate: { observer.rate(halfStars: $0) }
                )
            } else {
                PlayerTransportControls(observer: observer)
            }
            if showsVolume {
                Spacer().frame(height: 8)
                SystemVolumeSlider()
            }
            Spacer().frame(height: 12)
            PlayerSecondaryControls(
                observer: observer,
                showsChaptersControl: showsChapters,
                onShowChapters: { showChapterList = true }
            )
            Spacer().frame(height: 8)
        }
        .fixedSize(horizontal: false, vertical: true)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { controlsHeight = $0 }
    }

    /// A problem card is up: the scrubber has nothing to seek.
    var isShowingProblem: Bool {
        if case .problem = observer.card?.kind { return true }
        return false
    }

    func cover(side: CGFloat) -> some View {
        BookCoverImage(
            bookId: observer.currentBookId,
            coverPath: observer.coverPath,
            coverHash: observer.coverHash
        )
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: Radius.xl, style: .continuous))
        .shadow(color: .black.opacity(0.25), radius: 16, x: 0, y: 8)
        .accessibilityHidden(true)
    }

    // MARK: - Header

    func header(showsTitle: Bool) -> some View {
        FullScreenPlayerHeader(
            observer: observer,
            showsTitle: showsTitle,
            onDismiss: { dismiss() },
            onViewDetails: onViewDetails,
            onViewSeries: onViewSeries,
            onViewContributor: onViewContributor,
            onShowBoostPicker: { showBoostPicker = true }
        )
    }
}

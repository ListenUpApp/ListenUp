import SwiftUI
@preconcurrency import Shared

/// The full-screen audiobook player, presented by `MainTabView` as a `fullScreenCover` that zooms
/// out of the mini player's cover.
///
/// Surface: the cover's wash (player spec §3.9, the same `CoverThemeObserver` Book Detail and Home use), or the
/// player's warm neutral until it resolves or when the cover gives none — the player is content, and HIG, Materials:
/// "Don't use Liquid Glass in the content layer". Glass is kept for the floating header controls. The system
/// presentation supplies VoiceOver modality, the swipe-down dismiss, and the zoom back into the mini player.
///
/// Layout (`PlayerLayoutMode`, from the space actually available):
/// - stacked: header · cover · titles · scrubber · transport (or the state card) · volume · settings row
/// - compact height: the cover beside that column (a phone in landscape)
/// - regular: the column beside the always-visible "Up Next" chapter pane (iPad)
/// The cover takes the height the controls leave; when even the smallest cover would not fit
/// (small phones, AX text sizes) the column scrolls instead of clipping.
///
/// The header reads Collapse · where the audio is · More. When the book can't play, or has finished, the shared state
/// card takes the transport's place; everything else stays, so the reader's place stays in view (plan C5).
struct FullScreenPlayerView: View {
    let observer: PlayerObserver
    /// Dismiss the player and navigate to the current book's detail screen.
    var onViewDetails: () -> Void = {}
    /// Dismiss the player and navigate to the given series.
    var onViewSeries: (String) -> Void = { _ in }
    /// Dismiss the player and navigate to the given contributor (author or narrator).
    var onViewContributor: (String) -> Void = { _ in }

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

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
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
        // Only the background runs edge to edge; the controls keep clear of the home indicator. The wash is opaque, so
        // Reduce Transparency changes nothing here.
        .background(Self.wash(coverTheme.colors, scheme: colorScheme).ignoresSafeArea())
        .task(id: "\(observer.currentBookId ?? "")#\(observer.coverHash ?? "")") {
            if let bookId = observer.currentBookId { coverTheme.load(bookId: bookId, coverHash: observer.coverHash) }
        }
        .animation(CoverThemeMotion.animation(fadesIn: coverTheme.fadesIn, reduceMotion: reduceMotion),
                   value: coverTheme.colors)
        // A revoked book closes Now Playing (§5.6); the mini player then says why.
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

    /// The cover's wash, or the player's neutral when it has none.
    static func wash(_ colors: CoverColors?, scheme: ColorScheme) -> Color {
        colors?.wash ?? PlayerWashTone.color(for: scheme)
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

                NowPlayingUpNextPanel(observer: observer)
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
    ///
    /// One structure for both cases, so the measured controls keep a single identity. Two branches
    /// (a fitted stack, else a scroll view) left the fitted branch's controls starting from the
    /// height the scroll branch measured while the zoom presentation began at a tiny size — about
    /// 530pt instead of 330 on an iPhone 17e — and the cover stayed stuck at ~155pt with empty
    /// bands around it (Pass 7 Simulator matrix).
    @ViewBuilder
    private func stackedColumn(width: CGFloat, height: CGFloat, showsChapters: Bool) -> some View {
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
                controlsColumn(showsChapters: showsChapters, showsVolume: true)
                    .padding(.horizontal, margin)
            }
            .frame(minHeight: height)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    /// Titles, scrubber, transport, volume, and the secondary row at their natural height, which
    /// is measured so the cover can take exactly what is left.
    private func controlsColumn(showsChapters: Bool, showsVolume: Bool) -> some View {
        VStack(spacing: 0) {
            NowPlayingTitleBlock(observer: observer, onViewContributor: onViewContributor)
            Spacer().frame(height: 18)
            // Chapter-scoped progress — its own view so its per-frame position reads don't
            // re-evaluate the rest of the player. Still under a problem card, live on a finished book (a seek there is
            // Listen Again from that point, §4.5).
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
    private var isShowingProblem: Bool {
        if case .problem = observer.card?.kind { return true }
        return false
    }

    private func cover(side: CGFloat) -> some View {
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

    private var header: some View {
        HStack {
            Button { dismiss() } label: {
                headerGlyph("chevron.down")
            }
            .buttonStyle(.plain)
            .frame(minWidth: TapTarget.minimum, minHeight: TapTarget.minimum)
            .accessibilityLabel(String(localized: "player.collapse"))

            Spacer()

            // Where the audio comes from or goes to (§3.1): plain status text, not a chip (HIG; plan F7). Casting reads
            // in the tint's text ink.
            Text(observer.statusText)
                .font(.footnote)
                .foregroundStyle(observer.isCasting ? Color.luTintInk : Color.luOnWashSecondary)
                .multilineTextAlignment(.center)
                .lineLimit(1)

            Spacer()

            moreMenu
        }
        .frame(minHeight: Self.headerHeight)
        .padding(.horizontal, Spacing.m)
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

    /// The shared More menu (§5.5.7): the places, then Close Book in its own section, destructive (HIG, Menus). Go to
    /// Author and Go to Narrator are gone: the byline's names are the way there.
    private var moreMenu: some View {
        Menu {
            ForEach(Array(observer.menu.enumerated()), id: \.offset) { _, section in
                Section {
                    ForEach(section.rows, id: \.self) { row in
                        Button(role: section.isDestructive ? .destructive : nil) { perform(row) } label: {
                            Label(row.title, systemImage: row.systemImage)
                        }
                    }
                }
            }
        } label: {
            headerGlyph("ellipsis")
        }
        .frame(minWidth: TapTarget.minimum, minHeight: TapTarget.minimum)
        .contentShape(Rectangle())
        .accessibilityLabel(String(localized: "player.more_options"))
    }

    private func perform(_ row: PlayerMenuModel.Row) {
        switch row {
        case .goToBook: onViewDetails()
        case .goToSeries(let seriesId): onViewSeries(seriesId)
        case .openPdf: observer.openCurrentBookPdf()
        case .volumeBoost: showBoostPicker = true
        case .closeBook: observer.closeBook()
        }
    }
}

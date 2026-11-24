import SwiftUI

extension FullScreenPlayerView {
    /// Half open on a table. The standing half carries what is read at a distance — the header, the cover and a
    /// one-line title; the lying half what is touched — the scrubber, the transport (or the state card) and the
    /// settings row. A gap as tall as the hinge, plus a clearance either side, keeps everything off the crease.
    @ViewBuilder
    func tabletop(hinge: CGRect, in size: CGSize, onShowChapters: @escaping () -> Void) -> some View {
        let clearance = PlayerLayoutMode.foldClearance
        let lyingHalf = max(0, size.height - hinge.maxY - clearance)
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                header(showsTitle: false)
                GeometryReader { space in
                    cover(
                        side: max(
                            0,
                            min(space.size.width, space.size.height, PlayerLayoutMode.tabletopCoverMaximum)
                        )
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                Spacer().frame(height: 16)
                NowPlayingTitleBlock(observer: observer, onViewContributor: onViewContributor, form: .oneLine)
                    .padding(.horizontal, margin)
            }
            .padding(.bottom, clearance)
            .frame(height: max(0, hinge.minY))

            Color.clear.frame(height: hinge.height)

            TabletopLyingHalfControls(
                observer: observer,
                isShowingProblem: isShowingProblem,
                availableHeight: lyingHalf,
                margin: margin,
                onShowChapters: onShowChapters
            )
            .padding(.top, clearance)
        }
    }

    /// Half open like a book: the regular arrangement, split at the hinge rather than at a fixed pane width — the
    /// player column on the near page, the chapter panel on the far one.
    @ViewBuilder
    func book(hinge: CGRect, in size: CGSize, chapterPanelFocused: AccessibilityFocusState<Bool>.Binding) -> some View {
        let clearance = PlayerLayoutMode.foldClearance
        let columnWidth = max(0, hinge.minX - clearance)
        let paneWidth = max(0, size.width - hinge.maxX - clearance)
        let belowHeader = max(0, size.height - Self.headerHeight)
        let hasChapterPanel = Self.bookPostureShowsChapterPanel(chapterCount: observer.chapterRows.count)
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                header(showsTitle: false)
                if hasChapterPanel {
                    stackedColumn(width: columnWidth, height: belowHeader, showsChapters: false)
                } else {
                    // No chapters, so no panel: the cover takes the near page.
                    cover(side: max(0, min(columnWidth - 2 * margin, belowHeader - 2 * Spacing.m)))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .frame(width: columnWidth)

            Color.clear.frame(width: max(0, size.width - columnWidth - paneWidth))

            if hasChapterPanel {
                NowPlayingChapterPanel(observer: observer)
                    .frame(width: max(0, paneWidth - Self.panelGap))
                    .padding(.vertical, Spacing.m)
                    .padding(.trailing, Self.panelGap)
                    .accessibilityFocused(chapterPanelFocused)
            } else {
                // The facing page carries the controls, as Android's half-open layout does.
                ScrollView {
                    controlsColumn(showsChapters: false, showsVolume: true, showsSeries: true)
                        .padding(.horizontal, margin)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: size.height)
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(width: paneWidth)
            }
        }
    }

    /// Whether a book half open like a book shows the chapter panel on the far page: only when there are chapters to
    /// pick from. A chapterless book puts the cover on the near page and the controls on the far one instead.
    static func bookPostureShowsChapterPanel(chapterCount: Int) -> Bool {
        chapterCount > 0
    }

    /// Whether the resolved layout already shows the chapter panel: the wide window, and a phone half open like a book.
    static func showsChapterPanel(_ mode: PlayerLayoutMode) -> Bool {
        if case .book = mode { return true }
        return mode == .regular
    }
}

/// The tabletop's lying half: the controls a hand's width wide, centred.
private struct TabletopLyingHalfControls: View {
    let observer: PlayerObserver
    let isShowingProblem: Bool
    let availableHeight: CGFloat
    let margin: CGFloat
    let onShowChapters: () -> Void

    @State private var lyingHalfHeight: CGFloat = 0

    var body: some View {
        Group {
            if lyingHalfHeight <= availableHeight {
                controls
            } else {
                ScrollView { controls }
                    .scrollBounceBehavior(.basedOnSize)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var controls: some View {
        VStack(spacing: 20) {
            ChapterScrubberSection(observer: observer)
                .disabled(isShowingProblem)
            if let card = observer.card {
                PlayerStateCardView(
                    card: card,
                    onAction: { observer.onCardAction($0) },
                    onRate: { observer.rate(halfStars: $0) }
                )
            } else {
                PlayerTransportControls(observer: observer)
            }
            PlayerSecondaryControls(
                observer: observer,
                showsChaptersControl: true,
                onShowChapters: onShowChapters
            )
        }
        .frame(maxWidth: PlayerLayoutMode.tabletopControlsMaximumWidth)
        .padding(.horizontal, margin)
        .fixedSize(horizontal: false, vertical: true)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { lyingHalfHeight = $0 }
        .frame(maxWidth: .infinity)
    }
}

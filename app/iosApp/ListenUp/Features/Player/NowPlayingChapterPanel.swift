import SwiftUI

/// The chapter panel beside the player on iPad (player spec §3.11, §5.3; the `NP-iPad` board): "Chapters" over "68
/// chapters · 1 hr, 8 min", then every chapter. It replaces the Chapters button and its sheet wherever it shows (P22).
///
/// It opens centred on the chapter you're in, after it has laid out and without animating (§9.2). When the chapter
/// changes it follows only if the old current row was fully in view; once you've scrolled away it holds still and
/// offers "Back to Current Chapter" (§3.11). The decision is the shared `ChapterListFollow`, through
/// `ChapterPanelFollow`, so iPad, Android and the web cannot disagree.
///
/// A card on the wash (HIG, Materials: "Don't use Liquid Glass in the content layer"): the board's translucent fill,
/// opaque under Increase Contrast (the Color Set) and under Reduce Transparency (HIG, Accessibility). Its width is
/// `PlayerLayoutMode.panelWidth`, set by the player.
struct NowPlayingChapterPanel: View {
    let observer: PlayerObserver

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.chapterPanelShowsOpaqueCard) private var showsOpaqueCard
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    /// Which rows are fully in view, and the shared rule's answer when the chapter changes.
    @State private var follow = ChapterPanelFollow()
    /// The row the list rests on, centred: the current chapter's from the first layout.
    @State private var centredRowId: String?

    static let cornerRadius: CGFloat = 26

    init(observer: PlayerObserver) {
        self.observer = observer
        _centredRowId = State(initialValue: Self.currentRowId(in: observer))
    }

    /// The card's fill: the board's translucent `luPlayerPanel`, or an opaque surface under Reduce Transparency.
    static func cardFill(reduceTransparency: Bool) -> Color {
        reduceTransparency ? Color(.secondarySystemBackground) : .luPlayerPanel
    }

    private static func currentRowId(in observer: PlayerObserver) -> String? {
        observer.chapterRows.first { $0.index == observer.chapterIndex }?.id
    }

    var body: some View {
        let card = RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
        VStack(alignment: .leading, spacing: 0) {
            header
            chapterList
        }
        .padding(.top, Spacing.m)
        .padding(.horizontal, Spacing.xs)
        .padding(.bottom, Spacing.xs)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Self.cardFill(reduceTransparency: reduceTransparency || showsOpaqueCard), in: card)
        .overlay(card.strokeBorder(border, lineWidth: 0.5))
        .shadow(color: .black.opacity(0.08), radius: 12, x: 0, y: 6)
    }

    /// The board's white hairline in light; the system separator otherwise, which has its own Increase Contrast variant.
    private var border: Color {
        colorScheme == .light && colorSchemeContrast == .standard ? .white.opacity(0.9) : .luSeparator
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(String(localized: "player.chapters"))
                .font(.title3.bold())
                .foregroundStyle(.primary)
                .accessibilityAddTraits(.isHeader)
            Text(summary)
                .font(.footnote)
                .foregroundStyle(Color.luQuietInk)
        }
        // Level with the rows' text: the card's inset plus the row's own.
        .padding(.horizontal, Spacing.m)
        .padding(.bottom, Spacing.xs)
    }

    private var chapterList: some View {
        ScrollViewReader { proxy in
            VStack(spacing: Spacing.xs) {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(observer.chapterRows) { chapter in
                            let isCurrent = chapter.index == observer.chapterIndex
                            ChapterRow(
                                number: chapter.number,
                                title: chapter.title,
                                durationMs: chapter.durationMs,
                                isCurrent: isCurrent,
                                isPlaying: observer.isPlaying,
                                remainingMs: observer.progress.chapterRemainingMs,
                                chapterFraction: isCurrent ? { observer.progress.chapterFraction } : nil,
                                tint: .luTint,
                                onTap: { observer.selectChapter(index: chapter.index) }
                            )
                            .id(chapter.id)
                            .onScrollVisibilityChange(threshold: 1.0) { follow.setFullyVisible(chapter.index, $0) }
                            // A long jump drops rows from the lazy stack without a visibility change.
                            .onDisappear { follow.setFullyVisible(chapter.index, false) }
                        }
                    }
                    .scrollTargetLayout()
                }
                // The first layout already rests on the current row (§9.2: centring before the container has laid out
                // is what left the web's list at chapter 1).
                .scrollPosition(id: $centredRowId, anchor: .center)
                .task(id: observer.chapterIndex) {
                    // After the layout, so the jump lands; then by the shared rule on every chapter change.
                    follow.reduceMotion = reduceMotion
                    await Task.yield()
                    perform(follow.onCurrent(observer.chapterIndex), proxy)
                }

                if follow.showsBackToCurrent {
                    backToCurrent(proxy)
                }
            }
        }
    }

    /// "Back to Current Chapter", at the foot of the list while the current row is out of view (§3.11). A bordered
    /// coral button (HIG, Buttons), its words in the deeper coral ink so they read at 4.5:1 on the tint.
    private func backToCurrent(_ proxy: ScrollViewProxy) -> some View {
        Button {
            perform(follow.backToCurrent(), proxy)
        } label: {
            Text(String(localized: "player.np_back_to_current_ios"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.luTintInk)
                // Wraps rather than truncates at large text sizes (HIG, Typography).
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: TapTarget.minimum)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .tint(.luTint)
    }

    private func perform(_ move: ChapterPanelFollow.Move, _ proxy: ScrollViewProxy) {
        guard case .centre(let animated) = move, let target = Self.currentRowId(in: observer) else { return }
        if animated {
            withAnimation(.smooth) { proxy.scrollTo(target, anchor: .center) }
        } else {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { proxy.scrollTo(target, anchor: .center) }
        }
    }

    /// "68 chapters · 1 hr, 8 min": the Chapters sheet's words.
    private var summary: String {
        String(
            format: String(localized: "player.chapters_count_summary"),
            observer.totalChapters,
            DurationFormatting.abbreviated(ms: observer.bookDurationMs))
    }
}

extension EnvironmentValues {
    /// Draws the chapter panel's Reduce Transparency card whatever the system setting, which SwiftUI cannot override:
    /// for Xcode previews and the review snapshots. The system setting still applies everywhere else.
    @Entry var chapterPanelShowsOpaqueCard = false
}

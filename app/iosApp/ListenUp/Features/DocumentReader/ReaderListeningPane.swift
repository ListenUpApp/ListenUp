import SwiftUI

/// The reader's listening pane, beside the page when the screen has room (`ReaderLayoutMode.beside`): a small cover,
/// the title and chapter, the scrubber, the transport (or the state card in its place) and the settings row. Built from
/// Now Playing's own pieces on the same `PlayerObserver`, so the pane and the player never disagree, and it is not a
/// second player: opening the reader from Now Playing leaves Now Playing underneath, unchanged.
struct ReaderListeningPane: View {
    let observer: PlayerObserver

    /// The pane's cover stays small: the page is what is read here.
    static let coverMaximum: CGFloat = 160

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                BookCoverImage(
                    bookId: observer.currentBookId,
                    coverPath: observer.coverPath,
                    coverHash: observer.coverHash
                )
                .frame(width: Self.coverMaximum, height: Self.coverMaximum)
                .clipShape(RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
                .accessibilityHidden(true)
                Spacer().frame(height: 20)
                // Bylines are links that leave the reader; the pane keeps to what is playing.
                NowPlayingTitleBlock(observer: observer, onViewContributor: { _ in }, form: .oneLine)
                Spacer().frame(height: 18)
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
                Spacer().frame(height: 12)
                PlayerSecondaryControls(observer: observer, showsChaptersControl: false, onShowChapters: {})
            }
            .padding(.horizontal, Spacing.m)
            .padding(.vertical, Spacing.l)
        }
        .scrollBounceBehavior(.basedOnSize)
        .defaultScrollAnchor(.center)
        .background(Color(.secondarySystemBackground))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "player.np_now_playing_ios"))
    }

    /// A problem card is up: the scrubber has nothing to seek.
    private var isShowingProblem: Bool {
        if case .problem = observer.card?.kind { return true }
        return false
    }
}

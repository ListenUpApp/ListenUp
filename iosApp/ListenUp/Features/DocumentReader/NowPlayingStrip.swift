import SwiftUI
import Shared

/// The reader's docked now-playing strip: cover · "Ch. N · {time left}" · play/pause.
/// Reflects the shared `PlayerCoordinator` (global playback) and renders only while a
/// session is active — opening/reading the PDF never affects audio.
struct NowPlayingStrip: View {
    @Environment(\.dependencies) private var dependencies

    var body: some View {
        let player = dependencies.playerCoordinator
        // `.error` is "visible" (so the main mini-player shows the inline error+retry), but the
        // reader's strip has no honest content then — hide it rather than render a blank "0s" card.
        if player.isVisible, !player.isErrored {
            content(player)
        }
    }

    @ViewBuilder
    private func content(_ player: PlayerCoordinator) -> some View {
        HStack(spacing: 11) {
            BookCoverImage(bookId: player.currentBookId, coverPath: player.coverPath)
                .frame(width: 40, height: 40)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 1) {
                Text(player.chapterTitle ?? player.bookTitle)
                    .font(.subheadline.weight(.medium)).lineLimit(1)
                Text(formatTimeLeft(remainingMs: player.bookDurationMs - player.displayBookPositionMs))
                    .font(.caption).foregroundStyle(.secondary).monospacedDigit().lineLimit(1)
            }

            Spacer(minLength: 8)

            Button { player.togglePlayback() } label: {
                // `isPlaybackActive` (playing OR buffering) so the glyph reads "pause" during the
                // startup buffer, matching what a tap does.
                Image(systemName: player.isPlaybackActive ? "pause.fill" : "play.fill")
                    .font(.title3).foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(Color.listenUpOrange, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            }
            .accessibilityLabel(String(localized: player.isPlaybackActive ? "player.pause" : "player.play"))
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

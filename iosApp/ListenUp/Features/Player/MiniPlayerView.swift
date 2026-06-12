import SwiftUI
@preconcurrency import Shared

/// Mini player hosted by the tab view's bottom accessory.
///
/// The accessory supplies the Liquid Glass surface; this view only renders
/// content, adapting between an expanded rich row (above the full tab bar)
/// and a compact docked row (beside the minimized tab bar) based on the
/// accessory's placement. Tapping anywhere (except play) opens the
/// full-screen player.
struct MiniPlayerView: View {
    let observer: PlayerCoordinator
    var onTap: () -> Void

    @Environment(\.tabViewBottomAccessoryPlacement) private var placement

    var body: some View {
        let layout = MiniPlayerLayout.resolve(isInline: placement == .inline)
        Button(action: onTap) {
            Group {
                switch layout {
                case .expanded: expandedRow
                case .compact: compactRow
                }
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Layouts

    /// Rich form shown above the full tab bar: progress line + cover + title/chapter + time + play/pause.
    private var expandedRow: some View {
        VStack(spacing: 0) {
            GeometryReader { geometry in
                Rectangle()
                    .fill(Color.listenUpOrange)
                    .frame(width: geometry.size.width * CGFloat(observer.displayBookProgress))
            }
            .frame(height: 2)
            .background(Color.gray.opacity(0.2))

            HStack(spacing: 12) {
                cover(size: 44)

                VStack(alignment: .leading, spacing: 2) {
                    Text(observer.bookTitle)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    if let chapter = observer.chapterTitle {
                        Text(chapter)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }

                Spacer(minLength: 12)

                Text(formatTimeRemaining(observer.bookDurationMs - observer.displayBookPositionMs))
                    .font(.caption.width(.condensed))
                    .fontDesign(.rounded)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()

                playPauseButton
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }

    /// Compact form docked beside the minimized tab bar: cover + title + play/pause.
    private var compactRow: some View {
        HStack(spacing: 10) {
            cover(size: 32)
            Text(observer.bookTitle)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(1)
            Spacer(minLength: 8)
            playPauseButton
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    // MARK: - Shared elements

    private func cover(size: CGFloat) -> some View {
        BookCoverImage(coverPath: observer.coverPath, blurHash: observer.coverBlurHash)
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
    }

    private var playPauseButton: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            observer.togglePlayback()
        } label: {
            Image(systemName: observer.isPlaying ? "pause.fill" : "play.fill")
                .font(.title3)
                .foregroundStyle(Color.listenUpOrange)
                .contentTransition(.symbolEffect(.replace.downUp))
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(observer.isPlaying
            ? String(localized: "player.pause")
            : String(localized: "player.play"))
    }

    // MARK: - Helpers

    /// Format milliseconds as "-H:MM:SS" or "-M:SS"
    private func formatTimeRemaining(_ ms: Int64) -> String {
        let totalSeconds = max(0, ms / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60

        if hours > 0 {
            return String(format: "-%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "-%d:%02d", minutes, seconds)
        }
    }
}

// MARK: - Preview

#Preview {
    VStack {
        Spacer()
        // Preview requires observer; shown for layout reference only
        ZStack {
            Color.clear
        }
        .frame(height: 72)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 16)
    }
}

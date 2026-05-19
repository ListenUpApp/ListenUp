import SwiftUI
import Shared

/// Floating glass mini player shown above the tab bar during playback.
///
/// Displays current book info with a thin progress bar, cover art,
/// title, chapter name, time remaining, and a play/pause button.
/// Tapping anywhere (except play) opens the full-screen player.
///
/// Liquid Glass design:
/// - `.regularMaterial` background with `cornerRadius: 16`
/// - Gradient border stroke (white 0.3→0.1)
/// - Shadow with 0.12 opacity
struct MiniPlayerView: View {
    let observer: PlayerCoordinator
    var onTap: () -> Void

    /// Height of the mini player for layout calculations
    static let height: CGFloat = 72

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 0) {
                // Progress bar at top — thin orange line
                GeometryReader { geometry in
                    Rectangle()
                        .fill(Color.listenUpOrange)
                        .frame(width: geometry.size.width * CGFloat(observer.bookProgress))
                }
                .frame(height: 2)
                .background(Color.gray.opacity(0.2))

                // Content row
                HStack(spacing: 12) {
                    // Cover art thumbnail
                    BookCoverImage(
                        coverPath: observer.coverPath,
                        blurHash: observer.coverBlurHash
                    )
                    .frame(width: 44, height: 44)
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))

                    // Title and chapter
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

                    Spacer()

                    // Time remaining
                    Text(formatTimeRemaining(observer.bookDurationMs - observer.bookPositionMs))
                        .font(.caption.width(.condensed))
                        .fontDesign(.rounded)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()

                    // Play/pause button
                    Button(action: {
                        let generator = UIImpactFeedbackGenerator(style: .medium)
                        generator.impactOccurred()
                        observer.togglePlayback()
                    }) {
                        Image(systemName: observer.isPlaying ? "pause.fill" : "play.fill")
                            .font(.title3)
                            .foregroundStyle(Color.listenUpOrange)
                            .contentTransition(.symbolEffect(.replace.downUp))
                            .frame(width: 36, height: 36)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            .frame(height: Self.height)
            .background {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(.regularMaterial)
                    .overlay {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .strokeBorder(
                                LinearGradient(
                                    colors: [.white.opacity(0.3), .white.opacity(0.1)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 0.5
                            )
                    }
                    .shadow(color: .black.opacity(0.12), radius: 16, x: 0, y: 8)
            }
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
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
        .frame(height: MiniPlayerView.height)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 16)
    }
}

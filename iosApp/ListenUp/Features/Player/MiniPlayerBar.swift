import SwiftUI
@preconcurrency import Shared

/// The floating glass mini player that sits just above the tab bar.
///
/// Unlike the former `tabViewBottomAccessory` host, this bar owns its own
/// Liquid Glass surface (it no longer borrows the accessory's). Its cover art
/// carries the shared `PlayerMorph.coverID` geometry so it morphs into the
/// full-player hero when the user taps to expand. Tapping anywhere except the
/// play/pause control invokes `onTap` to expand the player; an upward swipe on the
/// bar also expands (via `onTap`).
struct MiniPlayerBar: View {
    let observer: PlayerCoordinator
    var namespace: Namespace.ID
    var onTap: () -> Void

    /// Height of the content row — used by the host to reserve tab content space.
    static let barHeight: CGFloat = 56
    /// Bottom padding that lifts the bar clear of the floating tab bar.
    static let tabBarClearance: CGFloat = 4

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 0) {
                contentRow
                progressLine
            }
            .frame(maxWidth: .infinity)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5)
            )
            .shadow(color: .black.opacity(0.12), radius: 10, x: 0, y: 4)
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        // Swipe up to expand. `simultaneousGesture` keeps both the bar's tap and
        // the inner play/pause `Button` working — the drag only acts on an
        // upward release, leaving taps to the buttons.
        .simultaneousGesture(
            DragGesture(minimumDistance: 10)
                .onEnded { value in
                    if PlayerGestureMath.shouldExpand(
                        translation: value.translation.height,
                        predictedEndTranslation: value.predictedEndTranslation.height
                    ) {
                        onTap()
                    }
                }
        )
    }

    // MARK: - Content

    private var contentRow: some View {
        HStack(spacing: 12) {
            cover

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

            playPauseButton
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var cover: some View {
        BookCoverImage(bookId: observer.currentBookId, coverPath: observer.coverPath, blurHash: observer.coverBlurHash)
            .frame(width: 40, height: 40)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .matchedGeometryEffect(id: PlayerMorph.coverID, in: namespace)
    }

    /// Thin overall-book progress line pinned to the bottom edge of the bar.
    private var progressLine: some View {
        GeometryReader { geometry in
            Rectangle()
                .fill(Color.listenUpOrange)
                .frame(width: geometry.size.width * CGFloat(observer.displayBookProgress))
        }
        .frame(height: 2)
        .background(Color.primary.opacity(0.08))
        .clipShape(
            UnevenRoundedRectangle(
                bottomLeadingRadius: 16,
                bottomTrailingRadius: 16,
                style: .continuous
            )
        )
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
}

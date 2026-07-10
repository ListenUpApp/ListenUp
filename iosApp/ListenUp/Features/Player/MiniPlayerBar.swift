import SwiftUI
import Shared

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
    /// Small gap between the bar and the (measured) floating tab bar it sits above.
    static let tabBarClearance: CGFloat = 4

    /// Counts user taps on play/pause so the haptic fires only on a deliberate tap — not on
    /// programmatic `isPlaying` changes (audio-session interruptions, route changes, end of book).
    @State private var playPauseTapCount = 0

    var body: some View {
        Button(action: onTap) {
            contentRow
                .frame(maxWidth: .infinity)
                .glassControl(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                // Progress reads as the bar's own bottom edge — a hairline pinned just
                // inside the glass, inset from the rounded corners — not a separate strip
                // stacked beneath the card.
                .overlay(alignment: .bottom) { progressLine }
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

    @ViewBuilder
    private var contentRow: some View {
        if observer.isErrored {
            errorRow
        } else {
            HStack(spacing: 12) {
                cover

                VStack(alignment: .leading, spacing: 2) {
                    Text(observer.bookTitle)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                        .lineLimit(1)
                }

                Spacer(minLength: 12)

                playPauseButton
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
    }

    /// Never-stranded failure surface: instead of the player vanishing on `.error`, the bar shows
    /// the failure message and a Retry that re-drives the errored book (`togglePlayback` → replay).
    private var errorRow: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.title3)
                .foregroundStyle(Color.listenUpOrange)
                .frame(width: 40, height: 40)

            Text(observer.errorMessage ?? String(localized: "common.something_went_wrong"))
                .font(.subheadline)
                .foregroundStyle(.primary)
                .lineLimit(2)

            Spacer(minLength: 12)

            Button { observer.togglePlayback() } label: {
                Text("book.detail_retry")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.listenUpOrange)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "book.detail_retry"))

            Button { observer.dismissError() } label: {
                Image(systemName: "xmark")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "common.dismiss"))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    /// "{Chapter} · {time left}" when a chapter is known, otherwise just the time left.
    /// Time-left reuses the reader strip's `formatTimeLeft` helper ("9h 51m left"); the
    /// single line truncates on narrow widths so the bar never crowds. Reads only scalar
    /// values off the observer (rule 8 — no bridged Kotlin objects in the view body).
    private var subtitle: String {
        let timeLeft = formatTimeLeft(remainingMs: observer.bookDurationMs - observer.displayBookPositionMs)
        if let chapter = observer.chapterTitle, !chapter.isEmpty {
            return "\(chapter) · \(timeLeft)"
        }
        return timeLeft
    }

    private var cover: some View {
        BookCoverImage(bookId: observer.currentBookId, coverPath: observer.coverPath, blurHash: observer.coverBlurHash)
            .frame(width: 40, height: 40)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .matchedGeometryEffect(id: PlayerMorph.coverID, in: namespace)
            .accessibilityHidden(true)
    }

    /// Overall-book progress as a slim hairline sitting *on* the bar's lower edge: a
    /// barely-there capsule track with a coral fill, inset from the rounded corners so it
    /// reads as the bar's own progress accent rather than a separate strip beneath it.
    private var progressLine: some View {
        GeometryReader { geometry in
            Capsule()
                .fill(Color.primary.opacity(0.06))
                .overlay(alignment: .leading) {
                    Capsule()
                        .fill(Color.listenUpOrange)
                        .frame(width: geometry.size.width * CGFloat(observer.displayBookProgress))
                }
        }
        .frame(height: 2.5)
        .padding(.horizontal, 12)
        .padding(.bottom, 5)
    }

    private var playPauseButton: some View {
        Button {
            playPauseTapCount += 1
            observer.togglePlayback()
        } label: {
            Group {
                if observer.isBuffering {
                    // Honest buffering: a spinner while the stream loads, matching the full player
                    // and Android — not a pause glyph implying audio is already flowing.
                    ProgressView()
                        .controlSize(.small)
                        .tint(Color.listenUpOrange)
                } else {
                    // `isPlaybackActive` here means "playing" (buffering handled above); reads
                    // "pause" while playing because a tap pauses.
                    Image(systemName: observer.isPlaybackActive ? "pause.fill" : "play.fill")
                        .font(.title3)
                        .foregroundStyle(Color.listenUpOrange)
                        .contentTransition(.symbolEffect(.replace.downUp))
                }
            }
            .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(observer.isPlaybackActive
            ? String(localized: "player.pause")
            : String(localized: "player.play"))
        .haptic(.toggleOn, trigger: playPauseTapCount)
    }
}

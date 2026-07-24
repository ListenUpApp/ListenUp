import SwiftUI

/// One book in the Series-detail "Books in Series" list. Cover + completion badge,
/// sequence eyebrow, title, an in-progress bar or metadata line, and a play/pause
/// button. The whole row navigates to the book; the button toggles playback.
struct SeriesBookRow: View {
    let book: BookRow
    let sequence: String?
    let progress: Float?
    let isFinished: Bool
    let isPlaying: Bool
    let onPlayTapped: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            cover
            VStack(alignment: .leading, spacing: 2) {
                if let sequence, !sequence.isEmpty {
                    Text(String(format: String(localized: "series.book_number"), sequence))
                        .font(.caption2.weight(.bold))
                        .kerning(0.4)
                        .textCase(.uppercase)
                        .foregroundStyle(Color.luTint)
                }
                Text(book.title)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                detail
            }
            Spacer(minLength: 8)
            playButton
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .contentShape(Rectangle())
    }

    private var cover: some View {
        BookCoverImage(book: book)
            .accessibilityHidden(true)
            .frame(width: 54, height: 54)
            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
            .overlay(alignment: .bottomTrailing) {
                if isFinished {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Color.luOnTint)
                        .frame(width: 22, height: 22)
                        .background(Circle().fill(Color.luTint))
                        .overlay(Circle().stroke(Color.luSurface2, lineWidth: 2))
                        .offset(x: 4, y: 4)
                }
            }
    }

    @ViewBuilder private var detail: some View {
        if let progress, progress > 0, !isFinished {
            HStack(spacing: 9) {
                ProgressBar(progress: progress)
                    .frame(width: 150, height: 4)
                Text("\(Int((progress * 100).rounded()))%")
                    .font(.caption).monospacedDigit()
                    .foregroundStyle(Color.luLabel2)
            }
            .padding(.top, 5)
        } else {
            Text(metadataText)
                .font(.footnote)
                .foregroundStyle(Color.luLabel2)
                .padding(.top, 2)
        }
    }

    private var metadataText: String {
        let duration = DurationFormatting.hoursMinutes(ms: book.duration)
        return isFinished
            ? "\(String(localized: "series.book_finished")) · \(duration)"
            : duration
    }

    private var playButton: some View {
        Button(action: onPlayTapped) {
            Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(isPlaying ? Color.luOnTint : Color.primary)
                .frame(width: 40, height: 40)
                .background(Circle().fill(isPlaying ? Color.luTint : Color.luFill))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isPlaying ? "Pause" : "Play")
    }
}

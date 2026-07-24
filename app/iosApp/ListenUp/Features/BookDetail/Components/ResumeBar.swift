import SwiftUI

/// Primary playback affordance for the redesigned Book Detail screen.
///
/// A prominent coral Resume/Play button beside the existing `DownloadButton`, with
/// an optional "Ch. N · {chapter}" / "{time left}" line and a thin coral progress
/// track below. When the book is finished it shows a "Finished" badge in place of
/// the remaining-time line.
///
/// Pure/presentational: it takes display values and closures. The assembly screen
/// wires `onResume` to `observer.play()` and the download closures to the observer's
/// download actions.
struct ResumeBar: View {
    /// Listen progress in 0...1, or `nil` when the book hasn't been started.
    let progress: Float?
    let isComplete: Bool
    /// Pre-formatted remaining time (e.g. "9h 59m left").
    let timeRemaining: String?
    /// Pre-formatted current-chapter label (e.g. "Ch. 1 · A Game Begins"); omitted when `nil`.
    let currentChapterLabel: String?
    let downloadState: DownloadUIState
    let downloadProgress: Float
    /// When false the Resume/Play control is disabled and relabeled "Unavailable offline".
    /// Driven by the evidence-based reachability signal: false only when the book isn't
    /// downloaded AND the server is genuinely unreachable right now; re-enables the instant
    /// any traffic proves otherwise. Defaults true.
    var canPlay: Bool = true
    /// When false the download control is disabled — no playback platform, or the server is
    /// genuinely unreachable right now (same evidence-based signal as `canPlay`).
    var canDownload: Bool = true
    let onResume: () -> Void
    let onDownload: () -> Void
    let onCancelDownload: () -> Void
    let onDeleteDownload: () -> Void

    private let controlHeight: CGFloat = 52

    /// In-progress = started but not finished.
    private var isInProgress: Bool { progress != nil && !isComplete }

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                resumeButton
                downloadControl
            }

            if isComplete {
                finishedBadge
            } else if isInProgress {
                progressDetail
            }

            if let progress, !isComplete {
                progressTrack(progress)
            }
        }
    }

    // MARK: - Resume button

    private var resumeButton: some View {
        PrimaryButton(title: resumeTitle, icon: canPlay ? "play.fill" : "cloud.slash.fill", action: onResume)
            .disabled(!canPlay)
            .accessibilityLabel(resumeAccessibilityLabel)
    }

    private var resumeTitle: String {
        if !canPlay { return String(localized: "book.detail_unavailable_offline") }
        return isInProgress
            ? String(localized: "book.detail_resume")
            : String(localized: "book.detail_play")
    }

    private var resumeAccessibilityLabel: Text {
        if isInProgress, let progress {
            let percent = Int((progress * 100).rounded())
            return Text(String(format: String(localized: "book.detail_resume_progress_a11y"), percent))
        }
        return Text(resumeTitle)
    }

    // MARK: - Download

    private var downloadControl: some View {
        DownloadButton(
            state: downloadState,
            progress: downloadProgress,
            onDownload: onDownload,
            onCancel: onCancelDownload,
            onDelete: onDeleteDownload
        )
        .frame(width: controlHeight, height: controlHeight)
        // Only the ability to START a new download is gated on reachability; an in-flight or
        // completed download stays interactive (cancel / delete work offline).
        .disabled(!canDownload && downloadState == .notDownloaded)
    }

    // MARK: - Progress detail line

    @ViewBuilder
    private var progressDetail: some View {
        HStack(spacing: 12) {
            if let currentChapterLabel {
                Text(currentChapterLabel)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Spacer(minLength: 0)
            }

            if let timeRemaining {
                Text(timeRemaining)
                    .layoutPriority(1)
            }
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
    }

    // MARK: - Finished badge

    private var finishedBadge: some View {
        HStack(spacing: 4) {
            Image(systemName: "checkmark.circle.fill")
            Text(String(localized: "book.detail_finished"))
        }
        .font(.footnote.weight(.medium))
        .foregroundStyle(Color.listenUpOrange)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Progress track

    private func progressTrack(_ progress: Float) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.listenUpOrange.opacity(0.2))
                Capsule()
                    .fill(Color.listenUpOrange)
                    .frame(width: geo.size.width * CGFloat(min(max(progress, 0), 1)))
            }
        }
        .frame(height: 4)
        .accessibilityHidden(true)
    }
}

// MARK: - Preview

#Preview("Resume bar — states") {
    VStack(spacing: 40) {
        // In-progress.
        ResumeBar(
            progress: 0.38,
            isComplete: false,
            timeRemaining: "9h 59m left",
            currentChapterLabel: "Ch. 1 · A Game Begins",
            downloadState: .notDownloaded,
            downloadProgress: 0,
            onResume: {},
            onDownload: {},
            onCancelDownload: {},
            onDeleteDownload: {}
        )

        // Not started.
        ResumeBar(
            progress: nil,
            isComplete: false,
            timeRemaining: nil,
            currentChapterLabel: nil,
            downloadState: .downloading,
            downloadProgress: 0.65,
            onResume: {},
            onDownload: {},
            onCancelDownload: {},
            onDeleteDownload: {}
        )

        // Finished.
        ResumeBar(
            progress: 1.0,
            isComplete: true,
            timeRemaining: nil,
            currentChapterLabel: nil,
            downloadState: .completed,
            downloadProgress: 1,
            onResume: {},
            onDownload: {},
            onCancelDownload: {},
            onDeleteDownload: {}
        )
    }
    .padding()
}

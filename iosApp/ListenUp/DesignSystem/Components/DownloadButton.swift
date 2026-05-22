import SwiftUI

/// Download button with visual state for book detail.
///
/// States:
/// - Not downloaded: Download icon
/// - Queued: Spinner
/// - Downloading: Circular progress with percentage
/// - Completed: Checkmark, tap to delete
/// - Failed/Partial: Retry icon
///
/// Liquid Glass: Uses `.regularMaterial` background with gradient stroke.
struct DownloadButton: View {
    let state: DownloadUIState
    let progress: Float
    let onDownload: () -> Void
    let onCancel: () -> Void
    let onDelete: () -> Void

    /// Tracks whether we've shown the checkmark long enough to transition to trash
    @State private var showTrash = false
    /// Tracks previous state to detect completion transition
    @State private var previousState: String = ""

    var body: some View {
        Button(action: action) {
            ZStack {
                // Glass background
                Circle()
                    .fill(.regularMaterial)
                    .overlay {
                        Circle()
                            .strokeBorder(
                                LinearGradient(
                                    colors: [.white.opacity(0.3), .white.opacity(0.1)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 0.5
                            )
                    }
                    .shadow(color: .black.opacity(0.08), radius: 4, x: 0, y: 2)

                // Icon / progress
                iconView
            }
            .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .onChange(of: state) { oldValue, newValue in
            if newValue == .completed && oldValue != .completed {
                showTrash = false
                withAnimation(.easeInOut.delay(2.0)) {
                    showTrash = true
                }
            } else if newValue != .completed {
                showTrash = false
            }
        }
        .onAppear {
            // If already completed on appear, show trash immediately
            if state == .completed {
                showTrash = true
            }
        }
    }

    private var action: () -> Void {
        switch state {
        case .queued, .downloading:
            return onCancel
        case .completed:
            return onDelete
        case .notDownloaded, .partial, .failed:
            return onDownload
        }
    }

    @ViewBuilder
    private var iconView: some View {
        switch state {
        case .notDownloaded:
            Image(systemName: "arrow.down.circle")
                .font(.title3)
                .foregroundStyle(Color.listenUpOrange)

        case .queued:
            ProgressView()
                .scaleEffect(0.8)

        case .downloading:
            ZStack {
                Circle()
                    .stroke(Color.listenUpOrange.opacity(0.3), lineWidth: 3)
                    .frame(width: 28, height: 28)
                Circle()
                    .trim(from: 0, to: CGFloat(progress))
                    .stroke(Color.listenUpOrange, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .frame(width: 28, height: 28)
                    .rotationEffect(.degrees(-90))
                Image(systemName: "xmark")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(.secondary)
            }

        case .completed:
            Group {
                if showTrash {
                    Image(systemName: "trash")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .transition(.scale.combined(with: .opacity))
                } else {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.green)
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.3), value: showTrash)

        case .partial, .failed:
            Image(systemName: "arrow.clockwise.circle")
                .font(.title3)
                .foregroundStyle(.red)

        }
    }
}

/// Expanded download button with label for wider layouts.
struct DownloadButtonExpanded: View {
    let state: DownloadUIState
    let progress: Float
    let onDownload: () -> Void
    let onCancel: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                iconView
                labelText
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(.regularMaterial)
                    .overlay {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(
                                LinearGradient(
                                    colors: [.white.opacity(0.3), .white.opacity(0.1)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 0.5
                            )
                    }
                    .shadow(color: .black.opacity(0.08), radius: 4, x: 0, y: 2)
            }
        }
        .buttonStyle(.plain)
    }

    private var action: () -> Void {
        switch state {
        case .queued, .downloading: return onCancel
        case .completed: return onDelete
        case .notDownloaded, .partial, .failed: return onDownload
        }
    }

    @ViewBuilder
    private var iconView: some View {
        switch state {
        case .notDownloaded:
            Image(systemName: "arrow.down.circle")
                .foregroundStyle(Color.listenUpOrange)
        case .queued:
            ProgressView()
                .scaleEffect(0.7)
        case .downloading:
            ZStack {
                Circle()
                    .trim(from: 0, to: CGFloat(progress))
                    .stroke(Color.listenUpOrange, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    .frame(width: 18, height: 18)
                    .rotationEffect(.degrees(-90))
            }
        case .completed:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .partial, .failed:
            Image(systemName: "arrow.clockwise.circle")
                .foregroundStyle(.red)
        }
    }

    @ViewBuilder
    private var labelText: some View {
        switch state {
        case .notDownloaded:
            Text(String(localized: "book_detail.download"))
                .foregroundStyle(.primary)
        case .queued:
            Text(String(localized: "book_detail.queued"))
                .foregroundStyle(.secondary)
        case .downloading:
            Text("\(Int(progress * 100))%")
                .foregroundStyle(.secondary)
                .monospacedDigit()
        case .completed:
            Text(String(localized: "book_detail.downloaded"))
                .foregroundStyle(.primary)
        case .partial, .failed:
            Text(String(localized: "common.retry"))
                .foregroundStyle(.red)
        }
    }
}

#Preview("Download States") {
    VStack(spacing: 20) {
        HStack(spacing: 16) {
            DownloadButton(state: .notDownloaded, progress: 0, onDownload: {}, onCancel: {}, onDelete: {})
            DownloadButton(state: .queued, progress: 0, onDownload: {}, onCancel: {}, onDelete: {})
            DownloadButton(state: .downloading, progress: 0.65, onDownload: {}, onCancel: {}, onDelete: {})
            DownloadButton(state: .completed, progress: 1, onDownload: {}, onCancel: {}, onDelete: {})
            DownloadButton(state: .failed, progress: 0.3, onDownload: {}, onCancel: {}, onDelete: {})
        }

        DownloadButtonExpanded(state: .notDownloaded, progress: 0, onDownload: {}, onCancel: {}, onDelete: {})
            .padding(.horizontal)
        DownloadButtonExpanded(state: .downloading, progress: 0.65, onDownload: {}, onCancel: {}, onDelete: {})
            .padding(.horizontal)
        DownloadButtonExpanded(state: .completed, progress: 1, onDownload: {}, onCancel: {}, onDelete: {})
            .padding(.horizontal)
    }
    .padding()
}

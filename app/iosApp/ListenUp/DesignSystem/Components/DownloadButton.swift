import SwiftUI

/// What the download control says and does in one state — pure, so it is testable without a view.
///
/// Every state has a spoken label that names the action a tap performs, and a value that says where
/// the download stands (HIG, VoiceOver; Accessibility — state never by colour or glyph alone).
struct DownloadControl: Equatable {
    /// What a single tap does. A finished download has no one-tap action: removing it is
    /// destructive, so it lives in a menu behind a confirmation (HIG, Menus; Alerts).
    enum TapAction: Equatable {
        case download
        case cancel
        case openMenu
    }

    let state: DownloadUIState
    let progress: Float

    var tapAction: TapAction {
        switch state {
        case .notDownloaded, .partial, .failed: .download
        case .queued, .downloading, .waitingForWifi: .cancel
        case .completed: .openMenu
        }
    }

    var accessibilityLabel: String {
        switch state {
        case .notDownloaded: String(localized: "book.detail_download")
        case .queued, .downloading, .waitingForWifi: String(localized: "book.detail_cancel_download")
        case .completed: String(localized: "book.detail_downloaded")
        case .partial, .failed: String(localized: "book.detail_retry_download")
        }
    }

    var accessibilityValue: String? {
        switch state {
        case .notDownloaded, .completed: nil
        case .queued: String(localized: "book.detail_queued")
        case .waitingForWifi: String(localized: "book.detail_waiting_for_wifi")
        case .downloading: Double(min(max(progress, 0), 1)).formatted(.percent.precision(.fractionLength(0)))
        case .failed: String(localized: "book.detail_download_failed_a11y")
        case .partial: String(localized: "book.detail_download_partial_a11y")
        }
    }
}

/// Download button with visual state for book detail.
///
/// States:
/// - Not downloaded: Download icon
/// - Queued: Spinner
/// - Waiting for Wi-Fi: Wi-Fi-off icon — the download is parked because "Download on Wi-Fi Only"
///   is on and the network is metered. A spinner here would claim progress that is not happening.
/// - Downloading: Circular progress; tap cancels
/// - Completed: Checkmark; tap opens a menu whose Delete Download confirms first
/// - Failed/Partial: Retry icon
///
/// A standard `.bordered` circle, not Liquid Glass: this sits in the content layer, and glass is
/// for the controls and navigation that float above content (HIG, Materials).
struct DownloadButton: View {
    let state: DownloadUIState
    let progress: Float
    let onDownload: () -> Void
    let onCancel: () -> Void
    let onDelete: () -> Void

    @State private var confirmingDelete = false

    private var control: DownloadControl { DownloadControl(state: state, progress: progress) }

    var body: some View {
        Group {
            switch control.tapAction {
            case .download:
                Button(action: onDownload) { iconView }
            case .cancel:
                Button(action: onCancel) { iconView }
            case .openMenu:
                Menu {
                    Button(role: .destructive) {
                        confirmingDelete = true
                    } label: {
                        Label(String(localized: "book.delete_download"), systemImage: "trash")
                    }
                } label: {
                    iconView
                }
                .menuStyle(.button)
            }
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.circle)
        .accessibilityLabel(control.accessibilityLabel)
        .accessibilityValue(control.accessibilityValue ?? "")
        .confirmationDialog(
            String(localized: "book.delete_download"),
            isPresented: $confirmingDelete,
            titleVisibility: .visible
        ) {
            Button(String(localized: "book.delete_download"), role: .destructive, action: onDelete)
            Button(String(localized: "common.cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "book.detail_you_can_redownload_anytime_by"))
        }
    }

    @ViewBuilder
    private var iconView: some View {
        Group {
            switch state {
            case .notDownloaded:
                Image(systemName: "arrow.down.circle")
                    .foregroundStyle(Color.listenUpOrange)

            case .queued:
                ProgressView()
                    .scaleEffect(0.8)

            case .waitingForWifi:
                Image(systemName: "wifi.slash")
                    .foregroundStyle(.secondary)

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
                        .font(.system(size: 9, weight: .bold)) // decorative fixed size
                        .foregroundStyle(.secondary)
                }

            case .completed:
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)

            case .partial, .failed:
                Image(systemName: "arrow.clockwise.circle")
                    .foregroundStyle(.red)
            }
        }
        .font(.title3)
        // The bordered circle adds its own padding around this, landing the whole control at or
        // above the 44pt minimum target (HIG, Accessibility).
        .frame(width: 34, height: 34)
    }
}

#Preview("Download States") {
    HStack(spacing: 16) {
        DownloadButton(state: .notDownloaded, progress: 0, onDownload: {}, onCancel: {}, onDelete: {})
        DownloadButton(state: .queued, progress: 0, onDownload: {}, onCancel: {}, onDelete: {})
        DownloadButton(state: .downloading, progress: 0.65, onDownload: {}, onCancel: {}, onDelete: {})
        DownloadButton(state: .completed, progress: 1, onDownload: {}, onCancel: {}, onDelete: {})
        DownloadButton(state: .failed, progress: 0.3, onDownload: {}, onCancel: {}, onDelete: {})
    }
    .padding()
}

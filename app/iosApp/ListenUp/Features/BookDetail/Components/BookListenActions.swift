import SwiftUI

/// Book Detail's actions: a full-width coral primary capsule — Play, Resume, Listen Again,
/// or Unavailable Offline — then Download and Add to Shelf as a pair of bordered capsules, and, while a download is
/// active, held or failed, one status line saying what is happening (canvas I-BD-States).
///
/// Bordered, not Liquid Glass: these scroll with the content, and the HIG keeps glass for the controls that float
/// above it (HIG, Materials) — `DownloadButton` makes the same call. One prominent button per view (HIG, Buttons).
struct BookListenActions: View {
    let label: BookPrimaryActionLabel
    let downloadState: DownloadUIState
    let downloadProgress: Float
    let downloadSizeBytes: Int64
    /// The download's bytes and failure, for the status line and Cancel's confirmation.
    var downloadSnapshot: DownloadStatusSnapshot = .notDownloaded
    let canDownload: Bool
    let onPrimary: () -> Void
    let onDownload: () -> Void
    var onPauseDownload: () -> Void = {}
    let onCancelDownload: () -> Void
    let onDeleteDownload: () -> Void
    let onAddToShelf: () -> Void
    /// The cover's harmonised coral for the primary fill; `nil` keeps the app's coral.
    var actionTint: Color?

    @State private var confirmingDelete = false
    @State private var confirmingCancel = false

    private var control: DownloadControl { DownloadControl(state: downloadState, progress: downloadProgress) }

    var body: some View {
        VStack(spacing: 10) {
            Button(action: onPrimary) {
                ActionLabel(title: label.title, systemImage: label.systemImage, isBusy: label.isBusy)
            }
            .prominentAction()
            .tint(actionTint)
            .buttonBorderShape(.capsule)
            .disabled(!label.isEnabled || label.isBusy)

            // Side by side while both labels fit; stacked at a large text size rather than wrapping a label
            // (HIG, Typography: "Make sure your app's layout adapts to all font sizes").
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    downloadCapsule
                    shelfCapsule
                }
                VStack(spacing: 10) {
                    downloadCapsule
                    shelfCapsule
                }
            }
            if let line = DownloadStatusLine.of(downloadState, snapshot: downloadSnapshot) {
                statusLine(line)
            }
        }
        // An alert (HIG, Alerts): what it frees and that your place is kept; Keep, with the cancel role, is the safe
        // default.
        .alert(RemoveDownloadCopy.title, isPresented: $confirmingDelete) {
            Button(RemoveDownloadCopy.keep, role: .cancel) {}
            // Not destructive: downloading again undoes it (HIG, Alerts — the destructive role is for what can't be).
            Button(RemoveDownloadCopy.remove, action: onDeleteDownload)
        } message: {
            Text(RemoveDownloadCopy.message(formattedSize: BookSize.label(downloadSizeBytes)))
        }
        // Cancel throws away what has arrived, so it asks when that is worth keeping (HIG, Alerts); Keep Downloading,
        // with the cancel role, is the safe default.
        .alert(String(localized: "book.download_cancel_confirm_title"), isPresented: $confirmingCancel) {
            Button(String(localized: "book.download_cancel_confirm_keep"), role: .cancel) {}
            Button(String(localized: "book.download_cancel_confirm_discard"), role: .destructive,
                   action: onCancelDownload)
        } message: {
            Text(String(localized: .bookDownloadCancelConfirmMessage(StorageFormat.byteSize(downloadSnapshot.bytes.done))))
        }
    }

    /// Cancel, asking first when it would throw away more than a little.
    private func requestCancel() {
        if DownloadCancelConfirmation.isNeeded(downloadSnapshot) {
            confirmingCancel = true
        } else {
            onCancelDownload()
        }
    }

    /// The line under the pair: what is happening in words, and Cancel where the capsule does something else.
    private func statusLine(_ line: DownloadStatusLine) -> some View {
        HStack(spacing: 8) {
            Text(line.text)
                .font(.footnote)
                .foregroundStyle(line.isFailure ? Color.red : Color.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            if line.offersCancel {
                Button(String(localized: "common.cancel"), action: requestCancel)
                    .buttonStyle(.borderless)
                    // The full 44 pt target (HIG, Accessibility), drawn as plain tinted text.
                    .frame(minHeight: 44)
            }
        }
        .frame(minHeight: 44)
        .padding(.horizontal, 4)
        .accessibilityElement(children: .contain)
    }

    // MARK: - Download

    /// Once saved, the capsule is Delete download with a trash bin (Simon, 2026-10-11). The tap only asks; the alert's
    /// own Remove deletes (HIG, Alerts).
    @ViewBuilder
    private var downloadCapsule: some View {
        Group {
            switch control.tapAction {
            case .download:
                Button(action: onDownload) { downloadLabel }
                    .disabled(!canDownload)
            case .pause:
                Button(action: onPauseDownload) { downloadLabel }
            case .cancel:
                Button(action: requestCancel) { downloadLabel }
            case .confirmDelete:
                Button { confirmingDelete = true } label: { downloadLabel }
            }
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.capsule)
        .controlSize(.large)
        .accessibilityLabel(downloadAccessibilityLabel)
        .accessibilityValue(control.accessibilityValue ?? "")
    }

    private var downloadLabel: some View {
        Label {
            Text(Self.downloadTitle(downloadState))
        } icon: {
            DownloadCapsuleIcon(state: downloadState, progress: downloadProgress)
        }
        .font(.subheadline.weight(.semibold))
        .lineLimit(1)
        .frame(maxWidth: .infinity)
    }

    /// Spec N2: before it is saved, the download names its size — "Download, 1.29 GB" — never the book's length.
    private var downloadAccessibilityLabel: String {
        downloadState == .notDownloaded
            ? String(localized: .bookDetailDownloadSizeA11Y(StorageFormat.byteSize(downloadSizeBytes)))
            : control.accessibilityLabel
    }

    // MARK: - Add to Shelf

    private var shelfCapsule: some View {
        Button(action: onAddToShelf) {
            Label(String(localized: "book.detail_add_to_shelf_ios"), systemImage: "text.badge.plus")
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.capsule)
        .controlSize(.large)
    }

    // MARK: - Pure mappings

    static func downloadTitle(_ state: DownloadUIState) -> String {
        switch state {
        case .completed: String(localized: "book.download_delete_ios")
        case .queued: String(localized: "book.download_queued_ios")
        case .waitingForWifi: String(localized: "book.download_waiting_for_wifi_ios")
        case .waiting: String(localized: "book.download_waiting_ios")
        case .failed, .partial: String(localized: "book.download_retry_ios")
        case .downloading, .resumingAfterClose: String(localized: "book.download_pause_ios")
        case .paused: String(localized: "book.download_resume_ios")
        case .notDownloaded: String(localized: "book.detail_download_ios")
        }
    }

    /// The symbol for states drawn with one; the moving states draw a progress ring instead (`DownloadCapsuleIcon`).
    static func downloadSymbol(_ state: DownloadUIState) -> String? {
        switch state {
        case .notDownloaded: "arrow.down.circle"
        case .queued: "clock"
        case .waitingForWifi: "wifi.slash"
        case .waiting: "clock.arrow.circlepath"
        case .downloading, .resumingAfterClose, .paused: nil
        case .completed: "trash"
        case .partial, .failed: "arrow.clockwise"
        }
    }
}

/// The download capsule's icon: a symbol, or for a download with bytes in it a progress ring — pause bars while it
/// moves, a down arrow once you paused it (canvas I-BD-States).
private struct DownloadCapsuleIcon: View {
    let state: DownloadUIState
    let progress: Float

    var body: some View {
        if let symbol = BookListenActions.downloadSymbol(state) {
            Image(systemName: symbol)
                .foregroundStyle(state == .failed ? Color.red : Color.accentColor)
        } else {
            ZStack {
                Circle().stroke(Color.accentColor.opacity(0.3), lineWidth: 2.4)
                Circle()
                    .trim(from: 0, to: CGFloat(min(max(progress, 0), 1)))
                    .stroke(state == .paused ? Color.secondary : Color.accentColor,
                            style: StrokeStyle(lineWidth: 2.4, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Image(systemName: state == .paused ? "arrow.down" : "pause.fill")
                    .font(.system(size: 8, weight: .bold)) // decorative fixed size, inside a fixed ring
            }
            .frame(width: 18, height: 18)
            .accessibilityHidden(true)
        }
    }
}

/// Remove Download's alert: what it frees on this device and that the book stays.
enum RemoveDownloadCopy {
    static var title: String { String(localized: "book.detail_remove_download_title_ios") }
    static var keep: String { String(localized: "book.detail_remove_download_keep") }
    static var remove: String { String(localized: "book.detail_remove_download_confirm") }

    /// `formattedSize` arrives formatted so the sentence stays testable.
    static func message(formattedSize: String) -> String {
        String(localized: .bookDetailRemoveDownloadBody(formattedSize))
    }
}

#Preview("Listen actions") {
    VStack(spacing: 32) {
        BookListenActions(label: BookPrimaryActionLabel(kind: .resume, isPreparing: false),
                          downloadState: .notDownloaded, downloadProgress: 0, downloadSizeBytes: 1_288_490_189,
                          canDownload: true, onPrimary: {}, onDownload: {}, onCancelDownload: {},
                          onDeleteDownload: {}, onAddToShelf: {})
        BookListenActions(label: BookPrimaryActionLabel(kind: .unavailableOffline, isPreparing: false),
                          downloadState: .notDownloaded, downloadProgress: 0, downloadSizeBytes: 1_288_490_189,
                          canDownload: false, onPrimary: {}, onDownload: {}, onCancelDownload: {},
                          onDeleteDownload: {}, onAddToShelf: {})
    }
    .padding()
}

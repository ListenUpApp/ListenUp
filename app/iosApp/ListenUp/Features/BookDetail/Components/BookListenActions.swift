import SwiftUI

/// Book Detail's actions (spec §3.2.1 D14/D15/D20): a full-width coral primary capsule — Play, Resume, Listen Again,
/// or Unavailable Offline — then Download and Add to Shelf as a pair of bordered capsules.
///
/// Bordered, not Liquid Glass: these scroll with the content, and the HIG keeps glass for the controls that float
/// above it (HIG, Materials) — `DownloadButton` makes the same call. One prominent button per view (HIG, Buttons).
struct BookListenActions: View {
    let label: BookPrimaryActionLabel
    let downloadState: DownloadUIState
    let downloadProgress: Float
    let downloadSizeBytes: Int64
    let canDownload: Bool
    let onPrimary: () -> Void
    let onDownload: () -> Void
    let onCancelDownload: () -> Void
    let onDeleteDownload: () -> Void
    let onAddToShelf: () -> Void
    /// The cover's harmonised coral for the primary fill; `nil` keeps the app's coral.
    var actionTint: Color?

    @State private var confirmingDelete = false

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
            case .cancel:
                Button(action: onCancelDownload) { downloadLabel }
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
        Label(Self.downloadTitle(downloadState), systemImage: Self.downloadSymbol(downloadState))
            .font(.subheadline.weight(.semibold))
            .lineLimit(1)
            .frame(maxWidth: .infinity)
    }

    /// Spec N2: before it is saved, the download names its size — "Download, 1.29 GB" — never the book's length.
    private var downloadAccessibilityLabel: String {
        downloadState == .notDownloaded
            ? String(format: String(localized: "book.detail_download_size_a11y"),
                     StorageFormat.byteSize(downloadSizeBytes))
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
        case .completed: String(localized: "book.delete_download")
        case .queued: String(localized: "book.detail_queued")
        case .waitingForWifi: String(localized: "book.detail_waiting_for_wifi")
        case .failed, .partial: String(localized: "book.detail_retry_download")
        case .downloading: String(localized: "book.detail_cancel_download")
        case .notDownloaded: String(localized: "book.detail_download_ios")
        }
    }

    static func downloadSymbol(_ state: DownloadUIState) -> String {
        switch state {
        case .notDownloaded: "arrow.down.circle"
        case .queued: "clock"
        case .waitingForWifi: "wifi.slash"
        case .downloading: "xmark.circle"
        case .completed: "trash"
        case .partial, .failed: "arrow.clockwise.circle"
        }
    }
}

/// Remove Download's alert (spec §2.8, the iOS Confirms board): what it frees on this device and that the book stays.
enum RemoveDownloadCopy {
    static var title: String { String(localized: "book.detail_remove_download_title_ios") }
    static var keep: String { String(localized: "book.detail_remove_download_keep") }
    static var remove: String { String(localized: "book.detail_remove_download_confirm") }

    /// `formattedSize` arrives formatted so the sentence stays testable.
    static func message(formattedSize: String) -> String {
        String(format: String(localized: "book.detail_remove_download_body"), formattedSize)
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

import Foundation
@preconcurrency import Shared

/// Where a book's download stands, projected once from the shared `BookDownloadStatus` to native values, so the
/// mapping below is testable without a Kotlin object.
enum DownloadStatusSnapshot: Equatable {
    case notDownloaded
    case inProgress(downloadingFiles: Int, completedFiles: Int, downloadedBytes: Int64, totalBytes: Int64,
                    resumingAfterClose: Bool)
    case completed(totalBytes: Int64)
    case failed(message: String)
    case paused(by: Pauser, downloadedBytes: Int64, totalBytes: Int64)

    /// Who stopped a paused download: the person (it waits for them), the system (it resumes by itself), or
    /// nobody — a cancelled partial, which nothing will resume.
    enum Pauser: Equatable {
        case you, system, cancelled
    }

    init(_ status: BookDownloadStatus) {
        switch status.sealedType() {
        case .notDownloaded:
            self = .notDownloaded
        case .inProgress(let progress):
            let s = progress.value
            self = .inProgress(downloadingFiles: Int(s.downloadingFiles), completedFiles: Int(s.completedFiles),
                               downloadedBytes: s.downloadedBytes, totalBytes: s.totalBytes,
                               resumingAfterClose: s.resumingAfterClose)
        case .completed(let done):
            self = .completed(totalBytes: done.value.totalBytes)
        case .failed(let failure):
            self = .failed(message: failure.value.errorMessage)
        case .paused(let paused):
            let s = paused.value
            let pauser: Pauser = switch s.reason {
            case .pausedByYou: .you
            case nil: .cancelled
            default: .system
            }
            self = .paused(by: pauser, downloadedBytes: s.downloadedBytes, totalBytes: s.totalBytes)
        }
    }

    /// The bytes on the device and the book's whole size; zero for states that carry neither.
    var bytes: (done: Int64, total: Int64) {
        switch self {
        case .inProgress(_, _, let done, let total, _), .paused(_, let done, let total): (done, total)
        case .completed(let total): (total, total)
        case .notDownloaded, .failed: (0, 0)
        }
    }
}

extension DownloadUIState {
    /// The control's state for a book's download.
    ///
    /// - Parameters:
    ///   - isWaitingForWifi: the shared availability's "Wi-Fi only, on a metered network, nothing moving".
    ///   - anotherBookDownloading: whether some other book's bytes are moving — what "Queued" waits behind.
    static func from(_ snapshot: DownloadStatusSnapshot, isWaitingForWifi: Bool,
                     anotherBookDownloading: Bool) -> DownloadUIState {
        switch snapshot {
        case .notDownloaded: return .notDownloaded
        case .completed: return .completed
        case .failed: return .failed
        case .paused(let pauser, _, _):
            switch pauser {
            case .you: return .paused
            case .system: return .waiting
            case .cancelled: return .partial
            }
        case .inProgress(let downloading, let completed, _, _, let resumingAfterClose):
            if isWaitingForWifi { return .waitingForWifi }
            if resumingAfterClose { return .resumingAfterClose }
            // Nothing of this book has moved yet while another book's bytes are: it is waiting its turn. Alone,
            // it is starting, which reads as downloading.
            if downloading == 0 && completed == 0 && anotherBookDownloading { return .queued }
            return .downloading
        }
    }
}

/// The line under the action row while a download is active, held or failed (canvas I-BD-States): what is
/// happening, in words, and Cancel where the capsule above does something else. Resting states have none.
struct DownloadStatusLine: Equatable {
    let text: String
    /// A failure reads in the system red.
    let isFailure: Bool
    /// Cancel sits on the line when the capsule pauses, resumes or retries instead.
    let offersCancel: Bool

    static func of(_ state: DownloadUIState, snapshot: DownloadStatusSnapshot) -> DownloadStatusLine? {
        let (done, total) = snapshot.bytes
        let amount = DownloadAmount.of(done, total)
        switch state {
        case .notDownloaded, .completed, .partial:
            return nil
        case .downloading:
            return DownloadStatusLine(text: String(localized: .bookDownloadStatusDownloading(amount)),
                                      isFailure: false, offersCancel: true)
        case .resumingAfterClose:
            return DownloadStatusLine(text: String(localized: "book.download_status_resuming_after_close"),
                                      isFailure: false, offersCancel: true)
        case .queued:
            return DownloadStatusLine(text: String(localized: "book.download_status_queued"),
                                      isFailure: false, offersCancel: false)
        case .waitingForWifi:
            return DownloadStatusLine(text: String(localized: "book.download_status_wifi_only"),
                                      isFailure: false, offersCancel: false)
        case .waiting:
            return DownloadStatusLine(text: String(localized: .bookDownloadStatusResumesAutomatically(amount)),
                                      isFailure: false, offersCancel: false)
        case .paused:
            return DownloadStatusLine(text: String(localized: .bookDownloadStatusPaused(amount)),
                                      isFailure: false, offersCancel: true)
        case .failed:
            let reason = if case .failed(let message) = snapshot { DownloadFailureReason.phrase(message) } else { "" }
            return DownloadStatusLine(text: String(localized: .bookDownloadStatusFailed(reason)),
                                      isFailure: true, offersCancel: true)
        }
    }
}

/// "212 of 640 MB": what has arrived, in the unit of the whole, which is named once.
enum DownloadAmount {
    static func of(_ done: Int64, _ total: Int64) -> String {
        let whole = max(0, total)
        let unit: ByteCountFormatter.Units = switch whole {
        case 1_000_000_000...: .useGB
        case 1_000_000...: .useMB
        default: .useKB
        }
        let part = ByteCountFormatter()
        part.countStyle = .file
        part.allowedUnits = unit
        part.includesUnit = false
        part.allowsNonnumericFormatting = false
        let all = ByteCountFormatter()
        all.countStyle = .file
        all.allowedUnits = unit
        all.allowsNonnumericFormatting = false
        return String(localized: .bookDownloadAmount(part.string(fromByteCount: min(max(0, done), whole)),
                                                     all.string(fromByteCount: whole)))
    }
}

/// The reason half of "Couldn't download · …": the stored message, as a phrase that follows the dot.
enum DownloadFailureReason {
    static func phrase(_ message: String) -> String {
        var phrase = message.trimmingCharacters(in: .whitespaces)
        if phrase.hasSuffix(".") { phrase.removeLast() }
        guard let first = phrase.first else { return phrase }
        return first.lowercased() + phrase.dropFirst()
    }
}

/// Whether Cancel asks first. Cancel throws away what has arrived of the unfinished files, so it confirms when
/// that is worth keeping: more than about 100 MB, or more than a quarter of the book (Simon, 2026-11-21).
enum DownloadCancelConfirmation {
    static let discardThresholdBytes: Int64 = 100_000_000

    static func isNeeded(_ snapshot: DownloadStatusSnapshot) -> Bool {
        let (done, total) = snapshot.bytes
        return done > discardThresholdBytes || (total > 0 && done * 4 > total)
    }
}

/// A Library cover's download badge (canvas I-Library): a 24 pt light circle at the art's bottom-trailing
/// corner, an SF Symbol inside; red for a failure. A book with no download shows none.
enum DownloadBadge: Equatable {
    case downloading(progress: Float)
    case queued
    case waitingForWifi
    case resumesAutomatically
    case paused
    case failed
    case downloaded

    /// After a swipe-away the badge stays the downloading ring; the words live on the row.
    static func of(_ state: DownloadUIState, snapshot: DownloadStatusSnapshot) -> DownloadBadge? {
        let (done, total) = snapshot.bytes
        switch state {
        case .notDownloaded, .partial: return nil
        case .downloading, .resumingAfterClose: return .downloading(progress: total > 0 ? Float(done) / Float(total) : 0)
        case .queued: return .queued
        case .waitingForWifi: return .waitingForWifi
        case .waiting: return .resumesAutomatically
        case .paused: return .paused
        case .failed: return .failed
        case .completed: return .downloaded
        }
    }

    var systemImage: String? {
        switch self {
        case .downloading: nil
        case .queued: "clock"
        case .waitingForWifi: "wifi.slash"
        case .resumesAutomatically: "clock.arrow.circlepath"
        case .paused: "pause.fill"
        case .failed: "exclamationmark"
        case .downloaded: "arrow.down.to.line"
        }
    }

    /// Spoken after the title: "Project Hail Mary, downloading, 33%".
    var accessibilityPhrase: String {
        switch self {
        case .downloading(let progress):
            String(localized: .libraryDownloadBadgeDownloadingA11Y(
                Double(min(max(progress, 0), 1)).formatted(.percent.precision(.fractionLength(0)))))
        case .queued: String(localized: "library.download_badge_queued_a11y")
        case .waitingForWifi: String(localized: "library.download_badge_waiting_for_wifi_a11y")
        case .resumesAutomatically: String(localized: "library.download_badge_resumes_automatically_a11y")
        case .paused: String(localized: "library.download_badge_paused_a11y")
        case .failed: String(localized: "library.download_badge_failed_a11y")
        case .downloaded: String(localized: "library.download_badge_downloaded_a11y")
        }
    }
}

/// The subtitle of a Storage › Downloads row for a book still downloading (canvas I-Library).
enum DownloadRowSubtitle {
    static func of(_ state: DownloadUIState, snapshot: DownloadStatusSnapshot) -> String {
        let (done, total) = snapshot.bytes
        let amount = DownloadAmount.of(done, total)
        return switch state {
        case .resumingAfterClose: String(localized: "book.download_status_resuming_after_close")
        case .queued: String(localized: "book.download_row_queued")
        case .waitingForWifi: String(localized: "book.download_row_waiting_for_wifi")
        case .waiting: String(localized: "book.download_row_resumes_automatically")
        case .paused, .partial: String(localized: .bookDownloadStatusPaused(amount))
        case .failed:
            if case .failed(let message) = snapshot {
                String(localized: .bookDownloadStatusFailed(DownloadFailureReason.phrase(message)))
            } else {
                String(localized: .bookDownloadStatusFailed(""))
            }
        case .downloading, .notDownloaded, .completed: String(localized: .bookDownloadStatusDownloading(amount))
        }
    }
}

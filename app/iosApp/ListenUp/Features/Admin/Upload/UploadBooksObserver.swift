import Foundation
import Shared
import UIKit

/// Observes `UploadBooksViewModel` — flattens `UploadBooksUiState` into a native `UploadBooksPhase`, turns
/// a picked folder or set of files into streaming upload candidates, and keeps the upload alive for as
/// long as iOS allows.
///
/// **What it holds for the length of an upload.** The picker's security-scoped access: the shared
/// `FileSource`s open their files lazily, file by file, as the request body drains them, so access
/// taken for the pick and released straight after would fail on the second file. And the
/// `UploadKeepAlive`: the screen stays awake, and a short background task lets the file in flight
/// finish if the app is sent to the background. iOS gives an app no more than that — a transfer of
/// this shape cannot continue in the background, so the screen asks people to keep ListenUp open.
///
/// A session failure reaches the user through the shared `ErrorBus`, which the ViewModel emits to and
/// `GlobalErrorObserver` presents (iosApp rule 10); the phase's own message is the screen's inline
/// account of the same thing, beside the way to start over.
@Observable
@MainActor
final class UploadBooksObserver {
    // MARK: - State

    private(set) var phase: UploadBooksPhase = .idle
    /// A selection refused before anything was sent, or one with nothing in it; nil when none.
    var notice: UploadNotice?
    /// The picked folder is being walked — brief, but a big folder on slow storage is not instant.
    private(set) var isPreparing = false

    // MARK: - Dependencies

    private let viewModel: UploadBooksViewModel
    private let bridge = FlowBridge()
    private let keepAlive = UploadKeepAlive()
    private var scopedURLs: [URL] = []

    init(viewModel: UploadBooksViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply(Self.phase(from: $0)) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    /// Upload the folder at `url`, keeping its shape under its own name.
    func uploadFolder(_ url: URL) {
        start(urls: [url]) { UploadSelection.folder(url) }
    }

    /// Upload loose files, each arriving under its own name.
    func uploadFiles(_ urls: [URL]) {
        start(urls: urls) { UploadSelection.files(urls) }
    }

    /// Stop the upload in flight. The shared repository abandons the server-side session.
    func cancel() { viewModel.cancel() }

    /// Dismiss a finished or failed run so another can start.
    func reset() { viewModel.reset() }

    // MARK: - Starting

    private func start(urls: [URL], select: @escaping @Sendable () -> [UploadPick]) {
        releaseAccess()
        scopedURLs = urls.filter { $0.startAccessingSecurityScopedResource() }
        isPreparing = true
        Task {
            // The walk reads the filesystem, so it runs off the main actor; only the `Sendable`
            // picks cross back. The Kotlin candidates are not `Sendable` and are built here.
            let picks = await Task.detached { select() }.value
            isPreparing = false
            offer(picks)
        }
    }

    private func offer(_ picks: [UploadPick]) {
        guard !picks.isEmpty else {
            releaseAccess()
            notice = .nothingFound
            return
        }
        let candidates = picks.map {
            UploadCandidate(
                relPath: $0.relPath,
                source: ExportedKotlinPackages.com.calypsan.listenup.client.core.fileSourceAtPath(path: $0.path)
            )
        }
        if let refusal = ExportedKotlinPackages.com.calypsan.listenup.client.presentation.admin.upload
            .uploadSelectionRefusal(candidates: candidates) {
            releaseAccess()
            notice = Self.notice(for: refusal)
            return
        }
        viewModel.onFilesPicked(candidates: candidates)
    }

    // MARK: - Lifetime

    private func apply(_ newPhase: UploadBooksPhase) {
        phase = newPhase
        if newPhase.isBusy {
            keepAlive.hold()
        } else {
            keepAlive.release()
            // While a pick is still being walked the ViewModel is idle but the files are about to
            // be opened; access goes only once nothing is left that could open one.
            if !isPreparing { releaseAccess() }
        }
    }

    private func releaseAccess() {
        scopedURLs.forEach { $0.stopAccessingSecurityScopedResource() }
        scopedURLs = []
    }

    // MARK: - Mapping

    nonisolated static func phase(from state: UploadBooksUiState) -> UploadBooksPhase {
        switch state.sealedType() {
        case .idle:
            return .idle
        case .uploading(let uploadingType):
            let uploading = uploadingType.value
            return .uploading(UploadProgressModel(
                filename: uploading.filename,
                fileNumber: Int(uploading.fileIndex) + 1,
                fileCount: Int(uploading.fileCount),
                fraction: uploading.fraction.map { Double($0) }
            ))
        case .finalizing:
            return .finalizing
        case .finished(let finishedType):
            let finished = finishedType.value
            return .finished(UploadOutcomeModel(
                imported: finished.imported.count,
                duplicates: finished.duplicates.count,
                failed: finished.failed.count
            ))
        case .error(let errorType):
            return .error(message: errorType.value.error.message)
        }
    }

    /// The sentence for a refused selection — the same words, and the same limits, as Android.
    nonisolated static func notice(for refusal: UploadSelectionRefusal) -> UploadNotice {
        switch refusal.sealedType() {
        case .tooManyFiles(let tooManyType):
            let refused = tooManyType.value
            return UploadNotice(
                title: String(localized: "admin.upload_books_too_many_files_title"),
                message: String(
                    format: String(localized: "admin.upload_books_too_many_files_body"),
                    Int(refused.limit), Int(refused.count)
                )
            )
        case .tooLarge(let tooLargeType):
            let refused = tooLargeType.value
            return UploadNotice(
                title: String(localized: "admin.upload_books_too_large_title"),
                message: String(
                    format: String(localized: "admin.upload_books_too_large_body"),
                    fileSize(refused.limitBytes), fileSize(refused.bytes)
                )
            )
        case .fileTooLarge(let fileTooLargeType):
            let refused = fileTooLargeType.value
            return UploadNotice(
                title: String(localized: "admin.upload_books_file_too_large_title"),
                message: String(
                    format: String(localized: "admin.upload_books_file_too_large_body"),
                    refused.filename, fileSize(refused.bytes), fileSize(refused.limitBytes)
                )
            )
        }
    }

    private nonisolated static func fileSize(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

// MARK: - Phase

/// Flattened upload state for a SwiftUI `switch`.
enum UploadBooksPhase: Equatable {
    case idle
    case uploading(UploadProgressModel)
    case finalizing
    case finished(UploadOutcomeModel)
    case error(message: String)

    /// Leaving now would abandon files already on the wire, or race the server's import of them.
    var isBusy: Bool {
        switch self {
        case .uploading, .finalizing: true
        case .idle, .finished, .error: false
        }
    }
}

/// A file on the wire, counted from one.
struct UploadProgressModel: Equatable {
    let filename: String
    let fileNumber: Int
    let fileCount: Int
    /// 0...1 across the whole session, or nil when the selection could not report its sizes — an
    /// indeterminate bar is honest where one pinned at zero is not.
    let fraction: Double?
}

/// How a finished session went. A duplicate is not a failure, so the three are counted apart.
struct UploadOutcomeModel: Equatable {
    let imported: Int
    let duplicates: Int
    let failed: Int
}

/// Why a selection was not sent.
struct UploadNotice: Equatable, Identifiable {
    let title: String
    let message: String
    var id: String { title + message }

    /// The picked folder, or the pick, had no files in it.
    static var nothingFound: UploadNotice {
        UploadNotice(
            title: String(localized: "admin.upload_books"),
            message: String(localized: "admin.upload_books_nothing_found")
        )
    }
}

// MARK: - Keep-alive

/// Keeps an upload going for as long as iOS allows: the screen stays awake while it runs, and a
/// background task lets the file in flight finish if ListenUp is sent to the background. Background
/// time is short and ends when iOS says so; the expiry handler gives the time back as it must.
@MainActor
private final class UploadKeepAlive {
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    func hold() {
        UIApplication.shared.isIdleTimerDisabled = true
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "upload-books") { [weak self] in
            // The expiry handler runs on the main thread (UIApplication's documented contract).
            MainActor.assumeIsolated { self?.endBackgroundTask() }
        }
    }

    func release() {
        UIApplication.shared.isIdleTimerDisabled = false
        endBackgroundTask()
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }
}

import SwiftUI
import Shared

/// The phase a first-run (or returning) admin's library is in, flattened from the KMP
/// ``LibraryReadiness`` sealed type for a SwiftUI `switch`. Mirrors the Android nav gate:
/// a setup question (`needsSetup`/`checkFailed`) always outranks population, which outranks
/// `ready`.
enum LibraryReadinessPhase: Equatable {
    case checking
    case needsSetup
    case populating
    case ready
    case checkFailed
}

/// Live initial-scan progress for the "Building your library" screen, flattened from the KMP
/// ``ScanProgressState`` into a native value type **at the observer boundary** — SwiftUI must
/// never diff bridged Kotlin objects (iosApp rule 8). `nil` (carried as the absence of this
/// value) marks the brief indeterminate "finishing up" tail after the scan completes but before
/// the books finish importing into Room.
struct ScanProgress: Equatable {
    /// Human phase label ("Discovering files", "Analyzing", "Saving library"…).
    let phaseDisplay: String
    /// The PERSISTING phase shows a "Saving N of M" count instead of a percentage/ETA.
    let isPersisting: Bool
    let filesDone: Int
    let filesTotal: Int
    /// 0…1 once the book total is known; `nil` during the indeterminate discovery phase.
    let fraction: Double?
    let books: Int
    let authors: Int
    let hours: Int
    let currentFile: String?
    let savingLabel: String
    /// Epoch-ms the scan started, for client-side ETA; `0` when unknown (suppresses ETA).
    let startedAtMs: Int64

    init(
        phaseDisplay: String,
        isPersisting: Bool,
        filesDone: Int,
        filesTotal: Int,
        fraction: Double?,
        books: Int,
        authors: Int,
        hours: Int,
        currentFile: String?,
        savingLabel: String,
        startedAtMs: Int64
    ) {
        self.phaseDisplay = phaseDisplay
        self.isPersisting = isPersisting
        self.filesDone = filesDone
        self.filesTotal = filesTotal
        self.fraction = fraction
        self.books = books
        self.authors = authors
        self.hours = hours
        self.currentFile = currentFile
        self.savingLabel = savingLabel
        self.startedAtMs = startedAtMs
    }

    init(from state: ScanProgressState) {
        self.init(
            phaseDisplay: state.phaseDisplayName,
            isPersisting: state.phase == "persisting",
            filesDone: Int(state.current),
            filesTotal: Int(state.filesTotal),
            fraction: state.progressFraction.map { Double($0) },
            books: Int(state.books),
            authors: Int(state.authors),
            hours: Int(state.hours),
            currentFile: state.currentFile,
            savingLabel: state.savingLabel,
            startedAtMs: state.startedAtMs
        )
    }
}

/// Observes ``AppStartupViewModel/readiness`` — the single authoritative answer to "what
/// should the authenticated app show right now?" — and exposes it as SwiftUI-native state.
/// Thin over ``FlowBridge``. Drives the post-auth onboarding gate at the app root:
/// `needsSetup` routes into the library-setup wizard; everything else mounts the main app.
@Observable
@MainActor
final class LibraryReadinessObserver {
    private(set) var phase: LibraryReadinessPhase = .checking

    /// Live scan progress while `phase == .populating`; `nil` otherwise (and during the
    /// indeterminate "finishing up" import tail).
    private(set) var scanProgress: ScanProgress?

    /// True once the initial population has gone quiet long enough to be considered stuck (see
    /// ``LibraryReadiness/Populating/stalled``). Drives the never-stranded "Continue with partial
    /// library" escape on ``LibraryScanView``. Always false outside `.populating`.
    private(set) var isPopulatingStalled: Bool = false

    private let appStartupViewModel: AppStartupViewModel
    private let bridge = FlowBridge()

    init(appStartupViewModel: AppStartupViewModel = KoinHelper.shared.getAppStartupViewModel()) {
        self.appStartupViewModel = appStartupViewModel
        bridge.bind(appStartupViewModel.readiness) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    /// Clear the needs-setup latch once the admin finishes the create-library wizard,
    /// flipping readiness to `ready` so the root mounts the main app.
    func onLibrarySetupComplete() {
        appStartupViewModel.onLibrarySetupComplete()
    }

    /// Escape a stalled initial population into the partial library already in Room. Latches the
    /// shared VM so readiness flips to `ready` and the shell mounts; books still scanning keep
    /// appearing as incremental sync lands them.
    func onContinueToPartialLibrary() {
        appStartupViewModel.onContinueToPartialLibrary()
    }

    /// Record when the app leaves the foreground so a long background can be detected on resume.
    func onAppBackgrounded() {
        appStartupViewModel.onAppBackgrounded()
    }

    /// On resume, re-run the library-setup check if the app was backgrounded past the staleness
    /// threshold; a short resume is a no-op.
    func onAppForegrounded() {
        appStartupViewModel.onAppForegrounded()
    }

    // MARK: - Mapping

    private func apply(_ readiness: LibraryReadiness) {
        switch onEnum(of: readiness) {
        case .checking:
            phase = .checking
            scanProgress = nil
            isPopulatingStalled = false
        case .needsSetup:
            phase = .needsSetup
            scanProgress = nil
            isPopulatingStalled = false
        case .populating(let populating):
            phase = .populating
            scanProgress = populating.progress.map { ScanProgress(from: $0) }
            isPopulatingStalled = populating.stalled
        case .ready:
            phase = .ready
            scanProgress = nil
            isPopulatingStalled = false
        case .checkFailed:
            phase = .checkFailed
            scanProgress = nil
            isPopulatingStalled = false
        case .unknown:
            Log.error("Unexpected LibraryReadiness case")
            phase = .checkFailed
            scanProgress = nil
            isPopulatingStalled = false
        }
    }
}

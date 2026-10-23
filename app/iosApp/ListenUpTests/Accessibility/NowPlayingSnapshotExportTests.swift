import SwiftUI
import Testing
import UIKit
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// Renders Now Playing per state, light and dark, to PNGs for the design review and for Simon. Off unless
/// LU_SNAPSHOT_DIR is set (xcodebuild passes TEST_RUNNER_LU_SNAPSHOT_DIR through), so it never runs in CI.
/// Test target only: a detached observer, no app launch, no audio.
@MainActor
@Suite("Now Playing review snapshots", .serialized,
       .enabled(if: ProcessInfo.processInfo.environment["LU_SNAPSHOT_DIR"] != nil))
struct NowPlayingSnapshotExportTests {
    enum Case: String, CaseIterable {
        case playing, paused, busy, sleepArmed, failed, offline, signedOut, held, finished, finishedNoNext, casting,
             wholeBook, largeText
    }

    enum Appearance: String, CaseIterable { case light, dark }

    private static let title = "Bridge Four"

    /// The Way of Kings' shape: 75 chapters of 20:35, so the scrubber, the times and the book sentence agree.
    nonisolated static let chapters = 75
    nonisolated static let chapterMs: Int64 = 1_235_000

    private func state(_ shot: Case) -> PlayerUiStateLoaded {
        switch shot {
        case .playing, .largeText:
            return .fixtureAt(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: Self.title)
        case .paused:
            return .fixtureAt(phase: PlayerPhasePaused.shared, currentIndex: 5, primary: PrimaryControlPlay.shared,
                            chapterTitle: Self.title)
        case .busy:
            return .fixtureAt(phase: PlayerPhaseBuffering.shared, currentIndex: 5, primary: PrimaryControlBusy.shared,
                            chapterTitle: Self.title, showsSkipBack: false)
        case .sleepArmed:
            let timer = SleepTimerStateActive(
                mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_421_000, totalMs: 1_800_000, startedAt: 0)
            return .fixtureAt(phase: PlayerPhasePlaying.shared, currentIndex: 5, sleepTimer: timer,
                            chapterTitle: Self.title)
        case .failed:
            return .fixtureAt(
                phase: PlayerPhaseFailed(error: PlaybackErrorConnectionLost(correlationId: "a1b2c3", debugInfo: nil)),
                currentIndex: 5, primary: PrimaryControlRetry.shared, chapterTitle: Self.title, showsSkipBack: false)
        case .offline:
            let offline = PlaybackErrorOfflineNotDownloaded(correlationId: nil, debugInfo: nil)
            return .fixtureAt(
                phase: PlayerPhaseUnavailable(reason: offline),
                currentIndex: 5, primary: PrimaryControlRetry.shared, chapterTitle: Self.title,
                source: AudioSourceOffline.shared, showsSkipBack: false)
        case .signedOut:
            return .fixtureAt(
                phase: PlayerPhaseUnavailable(reason: AuthErrorSessionExpired(correlationId: nil, debugInfo: nil)),
                currentIndex: 5, primary: PrimaryControlSignIn.shared, chapterTitle: Self.title, showsSkipBack: false)
        case .held:
            return .fixtureAt(
                phase: PlayerPhaseUnavailable(reason: BookErrorHeldForReview(correlationId: nil, debugInfo: nil)),
                currentIndex: 5, primary: PrimaryControlOpenInbox.shared, chapterTitle: Self.title,
                source: AudioSourceNotPlayable.shared, showsSkipBack: false)
        case .finished:
            return PlayerCardModelTests.finishedState(next: true)
        case .finishedNoNext:
            return PlayerCardModelTests.finishedState(next: false)
        case .casting:
            return .fixtureAt(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: Self.title,
                            source: AudioSourceCasting(deviceName: "Living Room"))
        case .wholeBook:
            return .fixtureAt(phase: PlayerPhasePlaying.shared, wholeBook: true)
        }
    }

    @Test(arguments: Case.allCases, Appearance.allCases)
    func export(_ shot: Case, _ appearance: Appearance) async throws {
        let dir = URL(fileURLWithPath: try #require(ProcessInfo.processInfo.environment["LU_SNAPSHOT_DIR"]))
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        let size = isPad ? CGSize(width: 1180, height: 820) : CGSize(width: 393, height: 852)
        let progress = Self.progress(in: state(shot))
        let hosted = await HostedView(
            FullScreenPlayerView(observer: .detached(state(shot), progress: progress)), size: size,
            dynamicTypeSize: shot == .largeText ? .accessibility2 : .large,
            appearance: appearance == .dark ? .dark : .light)
        defer { hosted.close() }
        let image = UIGraphicsImageRenderer(bounds: hosted.window.bounds).image { _ in
            hosted.window.drawHierarchy(in: hosted.window.bounds, afterScreenUpdates: true)
        }
        let name = "\(isPad ? "ipad" : "iphone")-\(shot.rawValue)-\(appearance.rawValue).png"
        try #require(image.pngData()).write(to: dir.appendingPathComponent(name))
    }
}

extension NowPlayingSnapshotExportTests {
    /// 12:04 into the current chapter, which puts the book at a floored percent with hours left: a coherent frame.
    static func progress(in state: PlayerUiStateLoaded) -> PlayerProgressRow {
        let chapter = state.chapter
        let into: Int64 = chapter.isWholeBook ? 6_899_000 : 724_000
        return PlayerProgressRow(PlayerProgress(
            chapterPositionMs: into, chapterDurationMs: chapter.durationMs,
            bookPositionMs: chapter.startMs + into, bookDurationMs: state.book.durationMs))
    }
}

private extension PlayerUiStateLoaded {
    /// `fixture` in The Way of Kings' shape (75 chapters of 20:35).
    static func fixtureAt(
        phase: any PlayerPhase = PlayerPhasePaused.shared,
        currentIndex: Int = 0,
        wholeBook: Bool = false,
        primary: any PrimaryControl = PrimaryControlPause.shared,
        sleepTimer: any SleepTimerState = SleepTimerStateInactive.shared,
        chapterTitle: String? = nil,
        source: any AudioSource = AudioSourceStreaming.shared,
        showsSkipBack: Bool = true
    ) -> PlayerUiStateLoaded {
        .fixture(
            phase: phase, currentIndex: currentIndex, wholeBook: wholeBook, primary: primary, sleepTimer: sleepTimer,
            chapterTitle: chapterTitle, source: source, showsSkipBack: showsSkipBack,
            chapterCount: NowPlayingSnapshotExportTests.chapters,
            chapterDurationMs: NowPlayingSnapshotExportTests.chapterMs)
    }
}

/// Renders the Now Playing sheets (player spec §5.5) to PNGs for the design review, at the default text size and at
/// accessibility size 3, light and dark. One coherent book throughout: The Way of Kings at 1.25×, 12:04 into Bridge
/// Four (8 min of it left), a 30-minute timer with 23:41 to go. Off unless LU_SNAPSHOT_DIR is set; no app, no audio.
@MainActor
@Suite("Player sheet review snapshots", .serialized,
       .enabled(if: ProcessInfo.processInfo.environment["LU_SNAPSHOT_DIR"] != nil))
struct PlayerSheetsSnapshotExportTests {
    enum Sheet: String, CaseIterable {
        case speed, sleepChoosing, sleepRunning, sleepFading, boost, chapters, bylineMenu, airPlayCasting
    }

    enum TextSize: String, CaseIterable { case large, ax3 }

    private static let narrators = [
        PlayerContributor(id: "n1", name: "Michael Kramer", bookCount: 14),
        PlayerContributor(id: "n2", name: "Kate Reading", bookCount: 11),
        PlayerContributor(id: "n3", name: "Steven Pacey", bookCount: 1),
        PlayerContributor(id: "n4", name: "Fiona Hardingham", bookCount: 3)
    ]

    private static func state(_ sheet: Sheet) -> PlayerUiStateLoaded {
        let timer: any SleepTimerState = sheet == .sleepRunning
            ? SleepTimerStateActive(
                mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_421_000, totalMs: 1_800_000, startedAt: 0)
            : SleepTimerStateInactive.shared
        let phase: any PlayerPhase = sheet == .sleepFading ? PlayerPhaseSleepFading.shared : PlayerPhasePlaying.shared
        let source: any AudioSource = sheet == .airPlayCasting
            ? AudioSourceCasting(deviceName: "Living Room") : AudioSourceDownloaded.shared
        return .fixture(
            phase: phase, currentIndex: 5, sleepTimer: timer, chapterTitle: "Bridge Four", source: source,
            boostDb: 6, narrators: narrators, chapterCount: NowPlayingSnapshotExportTests.chapters,
            chapterDurationMs: NowPlayingSnapshotExportTests.chapterMs, speed: 1.25)
    }

    @ViewBuilder
    private func view(_ sheet: Sheet, observer: PlayerObserver) -> some View {
        switch sheet {
        case .speed: SpeedSheet(observer: observer)
        case .sleepChoosing, .sleepRunning, .sleepFading: SleepSheet(observer: observer)
        case .boost:
            BoostPickerSheet(currentBoostDb: 6, defaultBoostDb: 0, onBoostSelected: { _ in }, onUseDefault: {})
        case .chapters: ChapterListSheet(observer: observer, onDismiss: {})
        case .bylineMenu:
            // A menu can't be captured open; its rows are what changed.
            List {
                Section(NowPlayingTitleBlock.menuHeader(isAuthors: false)) {
                    ForEach(NowPlayingTitleBlock.menuRows(observer.narrators)) { row in
                        VStack(alignment: .leading) {
                            Text(row.name)
                            if let books = row.books { Text(books).font(.footnote).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
        case .airPlayCasting: FullScreenPlayerView(observer: observer)
        }
    }

    @Test(arguments: Sheet.allCases, TextSize.allCases)
    func export(_ sheet: Sheet, _ textSize: TextSize) async throws {
        let dir = URL(fileURLWithPath: try #require(ProcessInfo.processInfo.environment["LU_SNAPSHOT_DIR"]))
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        let size = isPad ? CGSize(width: 1180, height: 820) : CGSize(width: 393, height: 852)
        let state = Self.state(sheet)
        let progress = NowPlayingSnapshotExportTests.progress(in: state)
        for appearance in NowPlayingSnapshotExportTests.Appearance.allCases {
            let hosted = await HostedView(
                view(sheet, observer: .detached(state, progress: progress)), size: size,
                dynamicTypeSize: textSize == .ax3 ? .accessibility3 : .large,
                appearance: appearance == .dark ? .dark : .light)
            let image = UIGraphicsImageRenderer(bounds: hosted.window.bounds).image { _ in
                hosted.window.drawHierarchy(in: hosted.window.bounds, afterScreenUpdates: true)
            }
            hosted.close()
            let name = "\(isPad ? "ipad" : "iphone")-sheet-\(sheet.rawValue)-\(textSize.rawValue)-\(appearance.rawValue).png"
            try #require(image.pngData()).write(to: dir.appendingPathComponent(name))
        }
    }
}

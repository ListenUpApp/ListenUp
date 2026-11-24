import SwiftUI
import AVFoundation
@preconcurrency import MediaPlayer
@preconcurrency import Shared

// The pure decision helpers (InterruptionPolicy, RouteChangePolicy, ScenePhasePolicy,
// LoadGeneration, ChapterMath) live in PlayerPolicies.swift.

/// The book the engine holds: what it reports progress against, and the lock screen shows.
struct EngineBook: Equatable, Sendable {
    let bookId: String
    /// Total book duration in milliseconds.
    let durationMs: Int64
}

/// The iOS engine adapter (player spec S1). The shared `PlayerSession` decides what is loaded and
/// what the user asked for; this executes `NativePlayerEngine`'s ordered instructions on AVAudioEngine
/// and reports what the engine actually does back as facts. It owns everything AVFoundation-shaped:
/// the audio session, interruptions and route changes, the gain stage, the lock screen and remote
/// commands, and position persistence (pause, seek, scene phase). Player UI reads `PlayerObserver`.
@Observable
final class PlayerCoordinator: RemoteCommandHandler {

    // MARK: - Engine state

    /// What the engine is doing. Every change is reported to the shared player.
    var enginePhase: EnginePhaseFact = .idle {
        didSet {
            facts.phase(enginePhase, generation: factGeneration)
            updateBandwidthSignal()
            saveRefinedMeasurement()
        }
    }

    /// The book the engine holds, or nil when nothing is loaded (or its load failed).
    var engineBook: EngineBook?

    var isPlaying: Bool { enginePhase == .playing }
    var isBuffering: Bool { enginePhase == .buffering }

    /// Playback intent is "advancing": the audio is playing OR buffering toward playing. Remote
    /// commands and the interruption policy treat the two as one.
    var isPlaybackActive: Bool { isPlaying || isBuffering }

    /// The book the engine holds — `nil` when nothing is loaded.
    var currentBookId: String? { engineBook?.bookId }

    // MARK: - Lock-screen metadata (set once per load)

    var bookTitle: String = ""
    var authorName: String = ""
    var coverPath: String?
    var playbackSpeed: Float = 1.0
    /// Volume-boost gain inputs for the loaded book. The boost API, the gain math and the
    /// measurement persistence live in `PlayerCoordinator+Gain.swift`; only the stored state
    /// stays here, because Swift extensions cannot declare stored properties.
    var gain = GainState()
    /// Bridged Kotlin chapters — read only by `ChapterMath`'s lock-screen window (in `remoteSeek` and
    /// `updateNowPlaying()`). Never fed to a view (rule 8).
    var chapters: [Chapter] = []
    /// The native chapter rows, for the memoized chapter index (the lock screen's re-stamp at a boundary).
    var chapterRows: [ChapterRowModel] = []
    /// Chapter identity tracks the COARSE position (see `refreshChapterIndex()`).
    var chapterIndex: Int = 0

    // MARK: - Skip intervals (observed from Settings, for the remote skip commands)

    private(set) var skipForwardSec: Int = 30
    private(set) var skipBackwardSec: Int = 10

    // MARK: - Position (PositionTracker)

    var bookDurationMs: Int64 { engineBook?.durationMs ?? 0 }

    /// Fine, per-frame position — the smooth scrubber thumb's source while a book is loaded.
    var bookPositionMs: Int64 { positionTracker.positionMs }

    /// Coarse, ~1×/sec position.
    var displayPositionMs: Int64 { positionTracker.displayPositionMs }

    /// The tracker's per-frame position while the engine holds a book, `nil` otherwise — what
    /// `PlayerObserver` reads for the smooth thumb before falling back to the shared 4 Hz progress.
    var livePositionMs: Int64? { engineBook == nil ? nil : positionTracker.positionMs }

    // MARK: - Components

    /// Internal, not private: extensions live in sibling `PlayerCoordinator+*.swift` files.
    let engine: PlaybackEngine
    let positionTracker = PositionTracker()
    /// Internal, not private: the Now-Playing bridge lives in `PlayerCoordinator+NowPlaying.swift`.
    let system: SystemIntegration

    // MARK: - Seam (native protocols — see EngineSeam.swift, PlaybackSeam.swift)

    /// Held for the coordinator's lifetime: production's `KotlinEngineInstructions` owns the FlowBridge that
    /// collects `NativePlayerEngine.commands`, and nothing else references it. Dropped, it cancels on deinit
    /// and every command from the shared player goes unread.
    private let instructions: EngineInstructionSource
    let facts: EngineFactReporting
    /// Internal, not private — see `engine` above.
    let progress: PlaybackProgressReporting
    /// Reactive source of the user's skip-interval settings, for the lock-screen skip commands.
    /// Optional so fake-injected tests skip it.
    private let skipIntervals: SkipIntervalProviding?
    /// Source of the audio-session interruption/route-change notifications this coordinator observes.
    /// Tests inject a private center so a post from one test can't reach another test's coordinator.
    let notificationCenter: NotificationCenter
    let bridge = FlowBridge()
    /// One step of the coordinator's ordered work: an instruction from the shared player, or a load's start once
    /// the engine holds the book.
    enum Step: Sendable {
        case instruction(EngineInstruction)
        case loadResolved(LoadedBook)
        case interruption(InterruptionPolicy.Action)
        /// The output device went away (headphones unplugged, AirPods out): pause, for good.
        case routeLost
        /// The system restarted its media services: the engine's player is dead and must be rebuilt.
        case mediaServicesReset
        case remote(RemoteAction)
    }
    /// A book `engine.load` has resolved, waiting its turn to start behind the instructions received before it.
    struct LoadedBook: Sendable {
        let bookId: String
        let prepared: PreparedPlayback
        let generation: Int
        let didLoad: Bool
    }
    /// Instructions join this queue the moment they arrive, and a load's start joins it when `engine.load`
    /// resolves — so everything received while the book loaded (a seek, a pause) runs before the book starts.
    let steps: AsyncStream<Step>.Continuation
    /// The one task running `steps`, in order (spec R2).
    private var drainTask: Task<Void, Never>?

    var lastReportedPositionMs: Int64 = 0
    var lastSyncedChapterIndex: Int = -1
    /// The in-flight engine load for the current `.load`, cancelled when another supersedes it.
    var loadTask: Task<Void, Never>?
    /// The shared player's tag for the `.load` being executed, carried by every fact reported about it, so a
    /// fact that reaches Kotlin after its next load is recognised as stale and dropped there.
    var factGeneration: EngineLoadGeneration = 0
    /// Bumped on every `.load` and `.stop`. An in-flight load compares the generation it captured
    /// against this after each `await` and abandons if superseded.
    var loadGeneration = 0
    /// True from a `.load` until its start runs, after `engine.load` resolves. While set, engine events are dropped —
    /// they belong to the outgoing book or the muted preroll — and transport instructions are
    /// recorded (`playWhenLoaded`, `pendingSeekMs`) and applied once the book has loaded.
    var isEngineLoading = false
    /// Whether the book being loaded starts playing when its load resolves (a `.pause` while loading clears it).
    var playWhenLoaded = true
    /// A `.seek` that arrived while the book was loading, applied once it has loaded.
    var pendingSeekMs: Int64?
    /// Why the system, not the user, paused playback — the pause's cause decides whether anything may restart it.
    enum PauseCause: Equatable, Sendable {
        /// An audio-session interruption (a call, Siri, an alarm): its end may resume, if the system says so.
        case interruption
        /// The output device went away: nothing but the user resumes, or the book would play out of the speaker.
        case routeLost
    }
    /// Set while playback is paused by the system, `nil` when it plays or the user paused it. Gates
    /// `.ended + .shouldResume`: a user's deliberate pause is never overridden.
    var pauseCause: PauseCause?
    static let positionReportIntervalMs: Int64 = 5000
    /// Feeds the shared "playback preempts downloads" signal. Optional for unit tests.
    let bandwidthCoordinator: PlaybackBandwidthCoordinator?
    /// Whether the loaded book streams (has a non-local file) — gates the yield signal.
    var isCurrentBookStreaming = false

    // MARK: - Init

    init(
        instructions: EngineInstructionSource,
        facts: EngineFactReporting,
        progress: PlaybackProgressReporting,
        engine: PlaybackEngine,
        system: SystemIntegration = SystemIntegration(),
        skipIntervals: SkipIntervalProviding? = nil,
        bandwidthCoordinator: PlaybackBandwidthCoordinator? = nil,
        notificationCenter: NotificationCenter = .default
    ) {
        self.instructions = instructions
        self.facts = facts
        self.progress = progress
        self.engine = engine
        self.system = system
        self.skipIntervals = skipIntervals
        self.bandwidthCoordinator = bandwidthCoordinator
        self.notificationCenter = notificationCenter
        let (stream, steps) = AsyncStream<Step>.makeStream(bufferingPolicy: .unbounded)
        self.steps = steps
        system.attach(handler: self)
        system.updateSkipIntervals(forwardSeconds: skipForwardSec, backwardSeconds: skipBackwardSec)
        bridge.bind(engine.events) { [weak self] in self?.handleEngineEvent($0) }
        observeSkipIntervals()
        observeAudioSession()
        instructions.deliver { steps.yield(.instruction($0)) }
        drainTask = Task { [weak self] in
            for await step in stream {
                switch step {
                case .instruction(let instruction): await self?.execute(instruction)
                case .loadResolved(let book): await self?.startLoaded(book)
                case .interruption(let action): await self?.applyInterruption(action)
                case .routeLost: await self?.applyRouteLoss()
                case .mediaServicesReset: await self?.applyMediaServicesReset()
                case .remote(let command): await self?.perform(command)
                }
            }
        }
    }

    isolated deinit {
        steps.finish()
        drainTask?.cancel()
    }

    /// Wires the Kotlin adapters; the now-playing bridge publishes through the engine's
    /// `MPNowPlayingSession` so the system reads state off the attached player.
    convenience init(deps: Dependencies) {
        let engine = AudioEngine()
        let kotlinEngine = deps.nativePlayerEngine
        self.init(
            instructions: KotlinEngineInstructions(engine: kotlinEngine),
            facts: KotlinEngineFacts(engine: kotlinEngine),
            progress: KotlinProgressReporting(reporter: deps.playbackProgressReporter),
            engine: engine,
            system: SystemIntegration(session: engine.nowPlayingSession),
            skipIntervals: KotlinSkipIntervalProviding(preferences: deps.playbackPreferences),
            bandwidthCoordinator: deps.playbackBandwidthCoordinator
        )
    }

    /// Track the user's skip-interval settings, so the lock-screen skip commands and their glyphs
    /// stay live with the setting.
    private func observeSkipIntervals() {
        guard let skipIntervals else { return }
        bridge.bind(skipIntervals.forwardSeconds) { [weak self] seconds in
            guard let self, seconds != skipForwardSec else { return }
            skipForwardSec = seconds
            system.updateSkipIntervals(forwardSeconds: seconds, backwardSeconds: skipBackwardSec)
        }
        bridge.bind(skipIntervals.backwardSeconds) { [weak self] seconds in
            guard let self, seconds != skipBackwardSec else { return }
            skipBackwardSec = seconds
            system.updateSkipIntervals(forwardSeconds: skipForwardSec, backwardSeconds: seconds)
        }
    }
}

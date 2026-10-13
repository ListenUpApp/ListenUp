import Foundation
@preconcurrency import Shared

/// `NativePlayerEngine.commands` as one ordered native stream: one `FlowBridge` binding (one task, so
/// order holds, rule 7) yields each mapped instruction into an `AsyncStream` the coordinator drains.
@MainActor
final class KotlinEngineInstructions: EngineInstructionSource {
    let instructions: AsyncStream<EngineInstruction>
    private let bridge = FlowBridge()

    init(engine: NativePlayerEngine) {
        let (stream, continuation) = AsyncStream<EngineInstruction>.makeStream(bufferingPolicy: .unbounded)
        instructions = stream
        bridge.bind(engine.commands) { command in
            if let instruction = Self.map(command) { continuation.yield(instruction) }
        }
    }

    static func map(_ command: any EngineCommand) -> EngineInstruction? {
        switch command.sealedType() {
        case .load(let load):
            .load(
                bookId: load.value.bookId,
                prepared: PreparedPlayback(kotlin: load.value.prepared),
                generation: load.value.generation
            )
        case .play: .play
        case .pause: .pause
        case .seekTo(let seek): .seek(toMs: seek.value.positionMs)
        case .setRate(let rate): .setRate(rate.value.speed, isReset: rate.value.isReset)
        case .setBoostDb(let boost): .setBoost(boost.value.boostDb, isReset: boost.value.isReset)
        case .setVolume(let volume): .setVolume(volume.value.volume)
        case .stop: .stop
        }
    }
}

/// Engine facts into `NativePlayerEngine`'s plain, non-suspend methods.
@MainActor
struct KotlinEngineFacts: EngineFactReporting {
    let engine: NativePlayerEngine

    func phase(_ phase: EnginePhaseFact, generation: EngineLoadGeneration) {
        let kotlinPhase: EnginePhase = switch phase {
        case .idle: .idle
        case .buffering: .buffering
        case .playing: .playing
        case .paused: .paused
        case .ended: .ended
        }
        engine.onPhase(generation: generation, phase: kotlinPhase)
    }

    func position(ms: Int64, generation: EngineLoadGeneration) {
        engine.onPosition(generation: generation, positionMs: ms)
    }
    func rate(_ rate: Float, generation: EngineLoadGeneration) { engine.onRate(generation: generation, speed: rate) }
    func boost(_ db: Float, generation: EngineLoadGeneration) { engine.onBoost(generation: generation, boostDb: db) }
    func route(_ deviceName: String?) { engine.onRoute(deviceName: deviceName) }

    func failure(_ failure: EngineFailure, generation: EngineLoadGeneration) {
        switch failure {
        case .connectionLost(let detail): engine.reportConnectionLost(generation: generation, debugInfo: detail)
        case .unsupportedFormat(let detail): engine.reportUnsupportedFormat(generation: generation, debugInfo: detail)
        case .couldNotStart(let detail): engine.reportCouldNotStart(generation: generation, debugInfo: detail)
        }
    }

    func unloaded(generation: EngineLoadGeneration) { engine.onUnloaded(generation: generation) }
}

/// The bridged Kotlin prepared book (the name collides with the native `PreparedPlayback` below).
typealias KotlinPreparedPlayback = ExportedKotlinPackages.com.calypsan.listenup.client.playback.PreparedPlayback

extension PreparedPlayback {
    /// The Kotlin prepared book as the native snapshot the coordinator plays: mapped once, here, so no
    /// Kotlin type crosses into the engine.
    init(kotlin prepared: KotlinPreparedPlayback) {
        self.init(
            bookTitle: prepared.bookTitle,
            bookAuthor: prepared.bookAuthor,
            bookNarrator: prepared.bookNarrator,
            coverPath: prepared.coverPath,
            resumeSpeed: prepared.resumeSpeed,
            resumeBoostDb: prepared.resumeBoostDb,
            measuredGainDb: prepared.measuredGainDb,
            normalizationGainDb: prepared.normalizationGainDb,
            resumePositionMs: prepared.resumePositionMs,
            chapters: Array(prepared.chapters),
            timeline: PreparedTimeline(
                totalDurationMs: prepared.timeline.totalDurationMs,
                files: prepared.timeline.files.map { file in
                    PreparedFile(
                        localPath: file.localPath,
                        streamingUrl: file.streamingUrl,
                        durationMs: file.durationMs,
                        startOffsetMs: file.startOffsetMs
                    )
                }
            ),
            seriesId: prepared.seriesId,
            authors: prepared.authors.map { ContributorNavRef(id: $0.id, name: $0.name) },
            narrators: prepared.narrators.map { ContributorNavRef(id: $0.id, name: $0.name) },
            coverHash: prepared.coverHash
        )
    }
}

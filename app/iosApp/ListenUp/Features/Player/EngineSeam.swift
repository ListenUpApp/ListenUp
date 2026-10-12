import Foundation

/// One instruction from the shared player, in the order it was issued. Native mirror of Kotlin's
/// `EngineCommand`; the Kotlin adapter maps it once, so the coordinator never sees a bridged type.
enum EngineInstruction: Sendable {
    case load(bookId: String, prepared: PreparedPlayback)
    case play
    case pause
    case seek(toMs: Int64)
    case setRate(Float, isReset: Bool)
    case setBoost(Float, isReset: Bool)
    case setVolume(Float)
    case stop
}

/// What the engine is doing, as the coordinator reports it to the shared player.
enum EnginePhaseFact: Sendable, Equatable { case idle, buffering, playing, paused, ended }

/// A typed engine failure (spec §4.6): mapped once, here at the engine edge, never from message text.
enum EngineFailure: Sendable, Equatable {
    case connectionLost(detail: String?)
    case unsupportedFormat(detail: String?)
    case couldNotStart(detail: String?)
}

/// The ordered instruction stream. One consumer: `PlayerCoordinator`'s drain task.
@MainActor
protocol EngineInstructionSource {
    var instructions: AsyncStream<EngineInstruction> { get }
}

/// Where the coordinator reports engine facts. `@MainActor`, like `PlaybackProgressReporting`.
@MainActor
protocol EngineFactReporting {
    func phase(_ phase: EnginePhaseFact)
    func position(ms: Int64)
    func rate(_ rate: Float)
    func boost(_ db: Float)
    func route(_ deviceName: String?)
    func failure(_ failure: EngineFailure)
    func unloaded()
}

import Foundation

/// The shared player's tag for one load. Every fact about that load carries it back, so the shared player can
/// drop a late fact about a book it has already moved on from.
typealias EngineLoadGeneration = Int32

/// One instruction from the shared player, in the order it was issued. Native mirror of Kotlin's
/// `EngineCommand`; the Kotlin adapter maps it once, so the coordinator never sees a bridged type.
enum EngineInstruction: Sendable {
    case load(bookId: String, prepared: PreparedPlayback, generation: EngineLoadGeneration = 0)
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

/// Where the coordinator reports engine facts. `@MainActor`, like `PlaybackProgressReporting`. Every fact about a
/// book carries the generation of the `.load` the coordinator is executing; the route is not about a book.
@MainActor
protocol EngineFactReporting {
    func phase(_ phase: EnginePhaseFact, generation: EngineLoadGeneration)
    func position(ms: Int64, generation: EngineLoadGeneration)
    func rate(_ rate: Float, generation: EngineLoadGeneration)
    func boost(_ db: Float, generation: EngineLoadGeneration)
    func route(_ deviceName: String?)
    func failure(_ failure: EngineFailure, generation: EngineLoadGeneration)
    func unloaded(generation: EngineLoadGeneration)
}

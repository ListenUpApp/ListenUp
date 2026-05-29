import Foundation
@testable import ListenUp

/// Records every command the coordinator sends the engine. An `actor` so it can
/// satisfy the `Sendable` `PlaybackEngine` protocol exactly as `AudioEngine` does.
actor FakePlaybackEngine: PlaybackEngine {
    private let continuation: AsyncStream<AudioEngineEvent>.Continuation
    nonisolated let events: AsyncStream<AudioEngineEvent>

    private(set) var didPlay = false
    private(set) var didPause = false
    private(set) var lastSeekMs: Int64?
    private(set) var lastRate: Float?
    private(set) var lastVolume: Float?
    private(set) var didRelease = false
    private(set) var didDeactivateSession = false

    init() {
        var c: AsyncStream<AudioEngineEvent>.Continuation!
        events = AsyncStream { c = $0 }
        continuation = c
    }

    /// Push an event into the coordinator's bound stream from a test.
    nonisolated func emit(_ event: AudioEngineEvent) { continuation.yield(event) }

    func load(segments: [AudioSegment], startPositionMs: Int64) async {}
    func play() async { didPlay = true }
    func pause() async { didPause = true }
    func seek(toMs positionMs: Int64) async { lastSeekMs = positionMs }
    func setRate(_ newRate: Float) async { lastRate = newRate }
    func setVolume(_ volume: Float) async { lastVolume = volume }
    func deactivateSession() async { didDeactivateSession = true }
    func release() async { didRelease = true }
}

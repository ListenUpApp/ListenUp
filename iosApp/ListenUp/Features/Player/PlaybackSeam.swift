import Foundation

/// The audio transport the coordinator drives. `AudioEngine` is the production
/// conformer; `FakePlaybackEngine` the test double. Platform-neutral — no
/// AVFoundation types cross this boundary, so it ports to macOS/watchOS as-is.
protocol PlaybackEngine: Sendable {
    /// The engine's event stream. Created once at init; consumed once.
    nonisolated var events: AsyncStream<AudioEngineEvent> { get }
    func load(segments: [AudioSegment], startPositionMs: Int64) async
    func play() async
    func pause() async
    func seek(toMs positionMs: Int64) async
    func setRate(_ newRate: Float) async
    /// Linear output gain 0.0...1.0. Used for the sleep-timer fade-out.
    func setVolume(_ volume: Float) async
    /// Deactivate the shared audio session so other apps' audio can resume.
    func deactivateSession() async
    func release() async
}

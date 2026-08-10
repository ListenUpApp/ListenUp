import Foundation
import Shared

extension Array where Element == Float {
    /// Bridges to a Kotlin `FloatArray` via a single bulk `memcpy` crossing.
    ///
    /// Swift Export cannot construct a Kotlin `FloatArray` from Swift — every generated
    /// initializer is `fatalError()` — and filling one through the generated `_set` would cost a
    /// bridge crossing per sample. So PCM crosses as `Data`, mirroring `Data.toKotlinByteArray()`.
    func toKotlinFloatArray() -> ExportedKotlinPackages.kotlin.FloatArray {
        let data = withUnsafeBufferPointer { Data(buffer: $0) }
        return ExportedKotlinPackages.com.calypsan.listenup.client.util.floatArrayFromNSData(
            data: data as NSData
        )
    }
}

/// Feeds the shared Kotlin R128 meter from the gain tap's `SampleRing`, entirely off the
/// real-time audio thread.
///
/// An `actor` because it owns a `Task` loop and a non-`Sendable` Kotlin meter: actor isolation
/// keeps both on one domain without an `@unchecked Sendable` promise. One feed per book — the
/// meter integrates loudness over everything it has been fed, so it must never span two books.
actor LoudnessMeterFeed {
    /// How often the ring is drained. Far below the ring's four-second capacity, so a busy
    /// system can miss several passes without losing coverage.
    private static let drainInterval = Duration.milliseconds(500)

    private let ring: SampleRing
    private var meter: LoudnessMeter?
    private var drainTask: Task<Void, Never>?
    private var isStopped = false

    init(ring: SampleRing) {
        self.ring = ring
    }

    /// Start the drain loop. Separate from `init` so the task never captures a half-built actor.
    func start() {
        guard drainTask == nil, !isStopped else { return }
        drainTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: LoudnessMeterFeed.drainInterval)
                // `weak self` plus the cancellation check: the loop can outlive neither a
                // released feed nor a `stop()`.
                guard !Task.isCancelled, let self else { return }
                await self.drainOnce()
            }
        }
    }

    /// The R128 normalization gain for everything measured so far, or `nil` until the meter has
    /// heard enough audio to clear the absolute gate.
    func currentGainDb() -> Float? {
        meter?.normalizationGainDb()
    }

    /// End the drain loop. Terminal — a new book gets a new feed, never a restarted one.
    func stop() {
        isStopped = true
        drainTask?.cancel()
        drainTask = nil
    }

    /// Move one pass of samples from the ring into the meter. Allocation is fine here; this runs
    /// on the cooperative pool, never the audio thread.
    private func drainOnce() {
        guard !isStopped else { return }
        if ring.hasFormatMismatch {
            Log.info("Loudness meter: audio format changed mid-book; measurement stopped")
            stop()
            return
        }
        guard let format = ring.format() else { return }
        let meter = meter ?? makeMeter(format: format)
        let samples = ring.drain()
        guard !samples.isEmpty else { return }
        meter.addFrames(
            interleaved: samples.toKotlinFloatArray(),
            frameCount: Int32(samples.count / format.channelCount)
        )
    }

    /// The meter is built lazily, on the first drain that finds a format: the tap's `prepare`
    /// callback is what fixes the sample rate and channel count, and it runs well after `load`.
    private func makeMeter(format: (sampleRate: Double, channelCount: Int)) -> LoudnessMeter {
        let created = LoudnessMeter(
            sampleRate: Int32(format.sampleRate),
            channelCount: Int32(format.channelCount)
        )
        meter = created
        return created
    }
}

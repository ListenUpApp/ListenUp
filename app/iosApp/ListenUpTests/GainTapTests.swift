import AVFoundation
import Foundation
import Shared
import Testing
@testable import ListenUp

/// Bridge probe for the shared Kotlin R128 meter. Swift Export cannot construct a Kotlin
/// `FloatArray` directly, so PCM crosses as `Data`; this proves the crossing carries real
/// samples and the meter measures them. If this breaks, the whole metering path is dead.
@Suite("LoudnessMeter bridge")
struct LoudnessMeterBridgeTests {
    @Test func kotlinMeterMeasuresSwiftSineWave() {
        let sampleRate = 48_000
        let meter = LoudnessMeter(sampleRate: Int32(sampleRate), channelCount: 1)
        let samples = (0..<sampleRate).map { index in
            Float(0.5 * sin(2 * Double.pi * 440 * Double(index) / Double(sampleRate)))
        }

        meter.addFrames(interleaved: samples.toKotlinFloatArray(), frameCount: Int32(samples.count))

        #expect(meter.normalizationGainDb() != nil)
    }
}

@Suite("Gain tap")
struct GainTapTests {
    @Test func gainCellRoundTripsLinearGain() {
        let cell = GainCell()
        #expect(abs(cell.linearGain - 1) < 0.0001)

        cell.setGain(db: 6)
        #expect(abs(cell.linearGain - 1.9953) < 0.001)

        cell.setGain(db: 0)
        #expect(abs(cell.linearGain - 1) < 0.0001)
    }

    @Test func sampleRingDropsOldestWhenFull() {
        let ring = SampleRing()
        // 1 Hz mono, so the ring's four-second capacity is exactly four samples.
        #expect(ring.setFormat(sampleRate: 1, channelCount: 1))

        let written: [Float] = [1, 2, 3, 4, 5]
        written.withUnsafeBufferPointer { ring.write($0.baseAddress!, count: $0.count) }

        #expect(ring.drain() == [2, 3, 4, 5])
    }

    @Test func sampleRingFormatIsSetOnce() {
        let ring = SampleRing()
        #expect(ring.setFormat(sampleRate: 48_000, channelCount: 2))

        #expect(ring.setFormat(sampleRate: 44_100, channelCount: 2) == false)

        #expect(ring.format()?.sampleRate == 48_000)
        #expect(ring.format()?.channelCount == 2)
        #expect(ring.hasFormatMismatch)
    }

    /// The latch is terminal in both directions: writes stop landing, and a second latch is a
    /// no-op — metering is off for the rest of the book, not merely paused.
    @Test func sampleRingLatchStopsFurtherWrites() {
        let ring = SampleRing()
        #expect(ring.setFormat(sampleRate: 1, channelCount: 1))

        #expect(ring.latchMismatch())

        let written: [Float] = [1, 2, 3]
        written.withUnsafeBufferPointer { ring.write($0.baseAddress!, count: $0.count) }
        #expect(ring.drain().isEmpty)
        #expect(ring.latchMismatch() == false)
    }

    /// Nothing to protect before a format is fixed — metering simply never started.
    @Test func sampleRingWithoutFormatCannotLatch() {
        let ring = SampleRing()

        #expect(ring.latchMismatch() == false)
        #expect(ring.hasFormatMismatch == false)
    }

    // MARK: - prepare

    @Test func prepareFixesRingFormatForInterleavedFloatPcm() {
        let context = GainTapContext(gain: GainCell(), ring: SampleRing())

        GainTap.adopt(processingFormat: floatPcmFormat(sampleRate: 48_000, channels: 2), context: context)

        #expect(context.ring.format()?.sampleRate == 48_000)
        #expect(context.ring.format()?.channelCount == 2)
        #expect(context.isGainSupported)
    }

    /// The regression: an interleaved segment fixes the format, then a NON-interleaved mono segment
    /// arrives mid-book. Its single buffer passes the process callback's buffer-count guard, so
    /// without a latch the meter would keep reading foreign samples under the old format and persist
    /// a wrong gain. Metering must stop instead — a wrong gain is worse than no gain.
    @Test func prepareLatchesMismatchWhenAudioTurnsNonInterleavedMidBook() {
        let context = GainTapContext(gain: GainCell(), ring: SampleRing())
        GainTap.adopt(processingFormat: floatPcmFormat(sampleRate: 48_000, channels: 2), context: context)

        GainTap.adopt(
            processingFormat: floatPcmFormat(sampleRate: 48_000, channels: 1, nonInterleaved: true),
            context: context
        )

        #expect(context.ring.hasFormatMismatch)                 // what LoudnessMeterFeed stops on
        #expect(context.ring.format()?.sampleRate == 48_000)    // the fixed format is not replaced
        #expect(context.ring.format()?.channelCount == 2)
        #expect(context.isGainSupported)                        // gain still applies; only metering stops
    }

    /// A book that is non-interleaved from its first segment never started metering, so there is
    /// nothing to latch — the ring stays clean and simply unmeasured.
    @Test func prepareLeavesRingCleanWhenNonInterleavedFromTheStart() {
        let context = GainTapContext(gain: GainCell(), ring: SampleRing())

        GainTap.adopt(
            processingFormat: floatPcmFormat(sampleRate: 48_000, channels: 2, nonInterleaved: true),
            context: context
        )

        #expect(context.ring.hasFormatMismatch == false)
        #expect(context.ring.format() == nil)
    }

    /// Anything but float PCM would be shredded by the gain multiply, so the whole tap goes inert.
    @Test func prepareDisablesGainForNonFloatAudio() {
        let context = GainTapContext(gain: GainCell(), ring: SampleRing())
        var format = floatPcmFormat(sampleRate: 48_000, channels: 2)
        format.mFormatFlags = kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked

        GainTap.adopt(processingFormat: format, context: context)

        #expect(context.isGainSupported == false)
        #expect(context.ring.format() == nil)
    }

    /// The processing format a tap's `prepare` hands over, as AVFoundation shapes it.
    private func floatPcmFormat(
        sampleRate: Double,
        channels: UInt32,
        nonInterleaved: Bool = false
    ) -> AudioStreamBasicDescription {
        let bytesPerFrame = nonInterleaved ? 4 : 4 * channels
        return AudioStreamBasicDescription(
            mSampleRate: sampleRate,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked
                | (nonInterleaved ? kAudioFormatFlagIsNonInterleaved : 0),
            mBytesPerPacket: bytesPerFrame,
            mFramesPerPacket: 1,
            mBytesPerFrame: bytesPerFrame,
            mChannelsPerFrame: channels,
            mBitsPerChannel: 32,
            mReserved: 0
        )
    }
}

/// The gain curve, and its agreement with the shared Kotlin specification.
///
/// `GainCurve` is a hand transcription of Kotlin `VolumeGain.applySample` — the audio thread cannot
/// call into the Kotlin runtime, so the math is duplicated on purpose. A duplicated constant that
/// can drift silently is how a fixed bug comes back, so the first test here pins the Swift copy to
/// the Kotlin original across the bridge.
@Suite("GainCurve")
struct GainCurveTests {
    @Test func saturationConstantsMatchTheSharedSpecification() {
        #expect(GainCurve.knee == VolumeGain.shared.KNEE_LINEAR)
        #expect(GainCurve.ceiling == VolumeGain.shared.CEILING_LINEAR)
    }

    /// Below the knee, the curve must be *exactly* a multiply — audio that was never going to clip
    /// is not to be touched by the saturator.
    @Test func belowTheKneeItIsExactlyAMultiply() {
        let gain = VolumeGain.shared.dbToLinear(db: 6)
        #expect(GainCurve.apply(0.1, gain) == 0.1 * gain)
        #expect(GainCurve.apply(-0.1, gain) == -0.1 * gain)
    }

    /// Driven hard, it approaches the ceiling and never reaches the rail. The old
    /// `min(1, max(-1, …))` returned exactly 1.0 here, flattening the waveform's tops.
    @Test func hardDrivingApproachesTheCeilingWithoutReachingFullScale() {
        let absurd = VolumeGain.shared.dbToLinear(db: 60)
        for sample in [Float(0.05), 0.3, 0.708, 1.0] {
            let out = GainCurve.apply(sample, absurd)
            #expect(out < 1)
            #expect(out <= GainCurve.ceiling)
            #expect(GainCurve.apply(-sample, absurd) == -out)
        }
    }

    /// Monotonic: louder in is always louder out. This is what keeps volume boost working — a
    /// curve that flattened would make the top of the boost range do nothing.
    @Test func theCurveIsMonotonicSoBoostAlwaysBoosts() {
        let gain = VolumeGain.shared.dbToLinear(db: 12)
        var previous = Float(0)
        for step in 1...200 {
            let out = GainCurve.apply(Float(step) / 200, gain)
            #expect(out > previous)
            previous = out
        }
    }

    /// Agreement with the Kotlin across the whole domain, not just at the constants — the point of
    /// the transcription is that it computes the same thing.
    @Test func theTranscriptionAgreesWithTheKotlinAcrossTheRange() {
        for gainDb in [Float(0), 3, 6, 12] {
            let gain = VolumeGain.shared.dbToLinear(db: gainDb)
            for step in 0...100 {
                let sample = Float(step) / 100
                let swift = GainCurve.apply(sample, gain)
                let kotlin = VolumeGain.shared.applySample(sample: sample, linearGain: gain)
                #expect(abs(swift - kotlin) < 1e-6)
            }
        }
    }
}

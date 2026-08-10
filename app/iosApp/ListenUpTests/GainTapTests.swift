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

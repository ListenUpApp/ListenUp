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
}

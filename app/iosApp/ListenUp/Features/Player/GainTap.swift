import AVFoundation
import MediaToolbox
import os

/// The live gain the audio thread reads, in linear (not dB) form.
///
/// The dB→linear conversion mirrors `VolumeGain.dbToLinear` in shared Kotlin, and the
/// multiply-and-clamp in `gainTapProcess` mirrors `VolumeGain.applySample`. They are duplicated
/// here on purpose: the real-time audio thread must never call into the Kotlin runtime, which
/// can allocate and take locks. The shared implementation stays the specification; this is its
/// audio-thread-safe transcription.
final class GainCell: Sendable {
    private let linear = OSAllocatedUnfairLock<Float>(initialState: 1)

    /// Set the boost in decibels. 0 dB is unity.
    func setGain(db: Float) {
        linear.withLock { $0 = powf(10, db / 20) }
    }

    /// The current linear multiplier. Read once per process callback, never per sample.
    var linearGain: Float {
        linear.withLock { $0 }
    }
}

/// A fixed-capacity ring of PCM samples: written by the real-time audio thread, drained by the
/// loudness-meter worker. Overwrite-oldest — falling behind costs coverage, never a stall on the
/// audio thread. Guarded by `os_unfair_lock`, the only lock cheap enough for that side.
///
/// The buffer is allocated once, in `setFormat`; `write` never allocates.
final class SampleRing: Sendable {
    /// How much audio the ring holds. Wide enough that a worker drain every 500 ms has eight
    /// passes of slack before the oldest samples roll off.
    private static let capacitySeconds = 4.0

    private struct State {
        var buffer: [Float] = []
        var writeIndex = 0
        var filled = 0
        var sampleRate: Double?
        var channelCount = 0
        var formatMismatch = false
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    /// Fix the ring's format and size its buffer. The first call wins.
    ///
    /// A later call with a *different* format returns `false`, latches `hasFormatMismatch`, and
    /// empties the buffer so every subsequent `write` is a no-op — mixing two formats into one
    /// measurement would produce a wrong gain, and a wrong gain is worse than no gain.
    /// Re-declaring the *same* format (each queue rebuild prepares a fresh tap) returns `true`.
    func setFormat(sampleRate: Double, channelCount: Int) -> Bool {
        state.withLock { state in
            if let existing = state.sampleRate {
                if existing == sampleRate && state.channelCount == channelCount { return true }
                state.formatMismatch = true
                state.buffer = []
                state.writeIndex = 0
                state.filled = 0
                return false
            }
            state.sampleRate = sampleRate
            state.channelCount = channelCount
            let capacity = Int(sampleRate * Self.capacitySeconds) * channelCount
            state.buffer = [Float](repeating: 0, count: max(1, capacity))
            return true
        }
    }

    /// The format fixed by the first `setFormat`, or `nil` before one succeeded.
    func format() -> (sampleRate: Double, channelCount: Int)? {
        state.withLock { state in
            guard let sampleRate = state.sampleRate else { return nil }
            return (sampleRate: sampleRate, channelCount: state.channelCount)
        }
    }

    /// `true` once a tap prepared with a format different from the fixed one. Terminal.
    var hasFormatMismatch: Bool {
        state.withLock { $0.formatMismatch }
    }

    /// Audio-thread side. Copies `count` samples in, overwriting the oldest on overflow.
    /// Allocation-free: the buffer already exists and is mutated in place.
    func write(_ samples: UnsafePointer<Float>, count: Int) {
        // `withLockUnchecked` because `withLock` needs a `@Sendable` body and the source pointer
        // isn't `Sendable`. Sound here: the pointer is only read inside the critical section, it
        // is valid for the whole process callback, and nothing derived from it escapes.
        state.withLockUnchecked { state in
            let capacity = state.buffer.count
            guard capacity > 0 else { return }
            // Only the newest `capacity` samples can survive the copy — skip the rest outright
            // rather than writing them and immediately overwriting them.
            let start = count > capacity ? count - capacity : 0
            for index in start..<count {
                state.buffer[state.writeIndex] = samples[index]
                state.writeIndex = (state.writeIndex + 1) % capacity
                if state.filled < capacity { state.filled += 1 }
            }
        }
    }

    /// Worker side. Takes everything written since the last drain, oldest first, and empties
    /// the ring. Allocates — which is fine, this never runs on the audio thread.
    func drain() -> [Float] {
        state.withLock { state in
            let capacity = state.buffer.count
            guard capacity > 0, state.filled > 0 else { return [] }
            let start = (state.writeIndex - state.filled + capacity) % capacity
            var drained = [Float]()
            drained.reserveCapacity(state.filled)
            for offset in 0..<state.filled {
                drained.append(state.buffer[(start + offset) % capacity])
            }
            state.filled = 0
            return drained
        }
    }
}

/// Everything a tap's C callbacks need, reached through `clientInfo`/tap storage. Retained when
/// the tap is created and released in the finalize callback — the tap outlives the Swift call
/// that made it.
final class GainTapContext: Sendable {
    let gain: GainCell
    let ring: SampleRing

    /// Cleared when `prepare` reports a processing format that is not 32-bit float PCM. Every
    /// sample below is read and written as `Float`; on any other format that arithmetic would
    /// shred the audio, so the tap goes inert instead.
    private let gainSupported = OSAllocatedUnfairLock<Bool>(initialState: true)

    init(gain: GainCell, ring: SampleRing) {
        self.gain = gain
        self.ring = ring
    }

    var isGainSupported: Bool {
        gainSupported.withLock { $0 }
    }

    func disableGain() {
        gainSupported.withLock { $0 = false }
    }
}

/// Builds the `AVAudioMix` that carries the volume-boost gain stage.
enum GainTap {
    /// Create a fresh tap and wrap it in a mix ready to assign to `AVPlayerItem.audioMix`.
    /// One mix per item — a tap belongs to exactly one item, so every item in a queue rebuild
    /// needs its own call.
    ///
    /// The input parameters are built *without* a track: `AVPlayerItem.asset` is `@MainActor` on
    /// the iOS 26 SDK, and a track-less parameters object applies to the item's audio anyway.
    static func makeMix(context: GainTapContext) -> AVAudioMix? {
        let clientInfo = UnsafeMutableRawPointer(Unmanaged.passRetained(context).toOpaque())
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: clientInfo,
            init: gainTapInit,
            finalize: gainTapFinalize,
            prepare: gainTapPrepare,
            unprepare: nil,
            process: gainTapProcess
        )
        var tap: MTAudioProcessingTap?
        let status = MTAudioProcessingTapCreate(
            kCFAllocatorDefault,
            &callbacks,
            kMTAudioProcessingTapCreationFlag_PostEffects,
            &tap
        )
        guard status == noErr, let tap else {
            // No tap means no finalize callback, so balance the retain here or the context leaks.
            Unmanaged<GainTapContext>.fromOpaque(clientInfo).release()
            Log.error("MTAudioProcessingTapCreate failed with status \(status); playing without gain")
            return nil
        }
        let parameters = AVMutableAudioMixInputParameters()
        parameters.audioTapProcessor = tap
        let mix = AVMutableAudioMix()
        mix.inputParameters = [parameters]
        return mix
    }
}

// MARK: - Tap callbacks
//
// `@convention(c)` function pointers: these capture nothing, and reach the context only through
// the tap's storage. Everything below `prepare` runs on the real-time audio thread — no
// allocation, no Kotlin, no Swift runtime calls that could take an unbounded lock.

private func gainTapInit(
    tap: MTAudioProcessingTap,
    clientInfo: UnsafeMutableRawPointer?,
    tapStorageOut: UnsafeMutablePointer<UnsafeMutableRawPointer?>
) {
    tapStorageOut.pointee = clientInfo
}

private func gainTapFinalize(tap: MTAudioProcessingTap) {
    Unmanaged<GainTapContext>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release()
}

private func gainTapPrepare(
    tap: MTAudioProcessingTap,
    maxFrames: CMItemCount,
    processingFormat: UnsafePointer<AudioStreamBasicDescription>
) {
    let context = Unmanaged<GainTapContext>
        .fromOpaque(MTAudioProcessingTapGetStorage(tap))
        .takeUnretainedValue()
    let format = processingFormat.pointee
    let isFloatPCM = format.mFormatID == kAudioFormatLinearPCM
        && format.mFormatFlags & kAudioFormatFlagIsFloat != 0
    guard isFloatPCM else {
        context.disableGain()
        Log.error("Gain tap: processing format is not 32-bit float PCM; gain and metering disabled")
        return
    }
    // Non-interleaved audio arrives as one buffer per channel. Re-interleaving it for the meter
    // would mean per-sample work on the audio thread, so those books stay unmetered — gain, which
    // needs no interleaving, still applies.
    guard format.mFormatFlags & kAudioFormatFlagIsNonInterleaved == 0 else {
        Log.info("Gain tap: non-interleaved audio; loudness metering off for this book")
        return
    }
    let alreadyMismatched = context.ring.hasFormatMismatch
    let accepted = context.ring.setFormat(
        sampleRate: format.mSampleRate,
        channelCount: Int(format.mChannelsPerFrame)
    )
    if !accepted && !alreadyMismatched {
        Log.info("Gain tap: audio format changed mid-book; loudness metering stopped")
    }
}

// Six parameters because `MTAudioProcessingTapProcessCallback` declares six — the signature is
// MediaToolbox's, not ours, and the function pointer won't convert if it differs.
// swiftlint:disable:next function_parameter_count
private func gainTapProcess(
    tap: MTAudioProcessingTap,
    numberFrames: CMItemCount,
    flags: MTAudioProcessingTapFlags,
    bufferListInOut: UnsafeMutablePointer<AudioBufferList>,
    numberFramesOut: UnsafeMutablePointer<CMItemCount>,
    flagsOut: UnsafeMutablePointer<MTAudioProcessingTapFlags>
) {
    let status = MTAudioProcessingTapGetSourceAudio(
        tap, numberFrames, bufferListInOut, flagsOut, nil, numberFramesOut
    )
    guard status == noErr else {
        // API contract: report zero frames produced when the source audio can't be fetched.
        numberFramesOut.pointee = 0
        return
    }
    let context = Unmanaged<GainTapContext>
        .fromOpaque(MTAudioProcessingTapGetStorage(tap))
        .takeUnretainedValue()
    guard context.isGainSupported else { return }
    let buffers = UnsafeMutableAudioBufferListPointer(bufferListInOut)
    // The meter measures PRE-GAIN samples: what the content sounds like, not what the boost
    // makes of it. Interleaved audio lives entirely in the first buffer; anything else was
    // already rejected in `prepare`, so the ring's buffer is empty and this write is a no-op.
    if buffers.count == 1, let first = buffers.first, let data = first.mData {
        context.ring.write(
            data.assumingMemoryBound(to: Float.self),
            count: Int(first.mDataByteSize) / MemoryLayout<Float>.size
        )
    }
    let linearGain = context.gain.linearGain
    for buffer in buffers {
        guard let data = buffer.mData else { continue }
        let samples = data.assumingMemoryBound(to: Float.self)
        let count = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
        for index in 0..<count {
            // Mirrors `VolumeGain.applySample` — see the note on `GainCell`.
            samples[index] = min(1, max(-1, samples[index] * linearGain))
        }
    }
}

package com.calypsan.listenup.server.transcode

import kotlin.concurrent.Volatile

/**
 * Publishes the boot probe's answer to everything that needs to know whether an encoder exists.
 *
 * The probe is asynchronous — it spawns FFmpeg and waits — while Koin construction is not, so the
 * result cannot simply be a constructor argument. Routes read this at request time instead, which
 * also means a probe that finishes late is honoured rather than baked in as "unavailable forever".
 *
 * ⚠️ Starts as [TranscoderStatus.Unavailable]. That is deliberate: before the probe answers, the
 * honest response to "can you transcode?" is no. A listener is told the server cannot convert for
 * them rather than being handed a stream that will never arrive.
 */
class TranscoderAvailability {
    @Volatile
    private var status: TranscoderStatus = TranscoderStatus.Unavailable("the encoder probe has not finished")

    @Volatile
    private var decoder: String? = null

    /** Records the probe's outcome. Called once at startup. */
    fun publish(
        probed: TranscoderStatus,
        probedDecoder: String? = null,
    ) {
        status = probed
        decoder = probedDecoder
    }

    /**
     * Path to the external FDK decoder, or null when there is none.
     *
     * Separate from [path] because it answers a different question: FFmpeg encodes everything, but
     * it cannot correctly *decode* xHE-AAC, and a server with an encoder but no FDK decoder must
     * refuse those sources rather than transcode them into silently truncated audio.
     */
    val decoderPath: String?
        get() = decoder

    /** The verified FFmpeg path, or null when no usable encoder was found. */
    val path: String?
        get() = (status as? TranscoderStatus.Available)?.path

    /** Whether a usable encoder exists right now. */
    val isAvailable: Boolean
        get() = path != null
}

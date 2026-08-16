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

    /** Records the probe's outcome. Called once at startup. */
    fun publish(probed: TranscoderStatus) {
        status = probed
    }

    /** The verified FFmpeg path, or null when no usable encoder was found. */
    val path: String?
        get() = (status as? TranscoderStatus.Available)?.path

    /** Whether a usable encoder exists right now. */
    val isAvailable: Boolean
        get() = path != null
}

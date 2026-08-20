package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.CodecCapability

/**
 * What this device can actually decode, answered by the platform at runtime.
 *
 * ⛔ **No cross-platform matrix lives in shared code**, and this function is why: each target
 * answers from its own runtime rather than from a table someone has to keep true. Android queries
 * `MediaCodecList` because xHE-AAC support is per-device, not per-OS-version; the JVM answers "all"
 * because the desktop app bundles FFmpeg; the browser asks itself with `canPlayType`. The one
 * exception is the Apple actual, which — lacking an equivalent runtime query — is a hand-maintained
 * static `setOf(...)`; that per-platform hardcoding is a smaller, contained cost than one shared
 * table trying to be true for four targets at once.
 *
 * Called by shared repository code around `prepare()`, so capabilities ride along on every platform
 * without any client having to remember to send them.
 */
expect fun platformCodecCapabilities(): Set<CodecCapability>

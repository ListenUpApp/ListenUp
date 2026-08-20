package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.CodecCapability

/**
 * What this device can actually decode, answered by the platform at runtime.
 *
 * ⛔ **No platform matrix lives anywhere in this codebase**, and this function is why: each target
 * answers from its own runtime rather than from a table someone has to keep true. Android queries
 * `MediaCodecList` because xHE-AAC support is per-device, not per-OS-version; the JVM answers "all"
 * because the desktop app bundles FFmpeg; the browser asks itself with `canPlayType`.
 *
 * Called by shared repository code around `prepare()`, so capabilities ride along on every platform
 * without any client having to remember to send them.
 */
expect fun platformCodecCapabilities(): Set<CodecCapability>

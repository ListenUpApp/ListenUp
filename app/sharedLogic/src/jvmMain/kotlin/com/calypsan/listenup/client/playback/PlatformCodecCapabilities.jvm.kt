package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.CodecCapability

/**
 * Everything: the desktop app decodes with its bundled bytedeco FFmpeg 8 rather than with whatever
 * codecs the host distro happens to ship. Declaring the full set is what keeps desktop off the
 * server's transcoder entirely.
 */
actual fun platformCodecCapabilities(): Set<CodecCapability> = CodecCapability.entries.toSet()

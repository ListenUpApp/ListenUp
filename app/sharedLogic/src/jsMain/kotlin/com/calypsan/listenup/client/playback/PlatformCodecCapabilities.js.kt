package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.CodecCapability
import kotlinx.browser.document
import org.w3c.dom.HTMLAudioElement

/**
 * `canPlayType` per candidate MIME, which is the browser's own answer rather than a user-agent
 * guess. ⚠️ It returns `"probably"`, `"maybe"` or `""` — **only `"probably"` is treated as a yes**,
 * because `"maybe"` on an audiobook means finding out hours in, and a needless transcode is far
 * cheaper than a listener discovering silence.
 *
 * The xHE-AAC probe is the load-bearing one: Chrome and Firefox answer `""`, Safari `"probably"`.
 */
actual fun platformCodecCapabilities(): Set<CodecCapability> {
    val probe = document.createElement("audio") as HTMLAudioElement

    // canPlayType is typed CanPlayTypeResult — an external interface over the JS string union, not a
    // Kotlin String — so it is compared through toString() rather than directly.
    fun canPlay(mime: String): Boolean = probe.canPlayType(mime).toString() == "probably"

    return buildSet {
        if (canPlay("audio/mpeg")) add(CodecCapability.MP3)
        if (canPlay("audio/mp4; codecs=\"mp4a.40.2\"")) add(CodecCapability.AAC_LC)
        if (canPlay("audio/mp4; codecs=\"mp4a.40.5\"")) add(CodecCapability.AAC_HE)
        if (canPlay("audio/mp4; codecs=\"mp4a.40.29\"")) add(CodecCapability.AAC_HE_V2)
        if (canPlay("audio/mp4; codecs=\"mp4a.40.42\"")) add(CodecCapability.AAC_XHE)
        if (canPlay("audio/flac")) add(CodecCapability.FLAC)
        if (canPlay("audio/ogg; codecs=\"opus\"")) add(CodecCapability.OPUS)
        if (canPlay("audio/ogg; codecs=\"vorbis\"")) add(CodecCapability.VORBIS)
    }
}

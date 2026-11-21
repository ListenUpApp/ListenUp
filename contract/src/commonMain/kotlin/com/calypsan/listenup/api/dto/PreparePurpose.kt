package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a client means to do with the signed audio URLs it asks `prepare()` for.
 *
 * The two uses hold a URL for very different spans. A stream is fetched while someone listens, so
 * a URL that lives for a listening session is enough. A download may wait for Wi-Fi overnight or
 * resume a half-fetched file days later, and the resume carries the URL it started with, so a URL
 * that dies first turns every long wait into a restart from zero.
 */
@Serializable
enum class PreparePurpose {
    /** Play now. URLs live for a listening session, and a transcoded stream is offered when needed. */
    @SerialName("stream")
    Stream,

    /**
     * Fetch the original files to keep on the device. URLs live as long as a download may take, and
     * no transcoded stream is offered: a download is always the original file.
     */
    @SerialName("download")
    Download,
}

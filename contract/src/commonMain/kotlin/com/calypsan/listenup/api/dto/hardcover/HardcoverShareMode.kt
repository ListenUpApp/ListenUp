package com.calypsan.listenup.api.dto.hardcover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * When ListenUp updates the listener's Hardcover — their choice, kept on the server and carried on
 * [HardcoverConnection.Connected.shareMode]. It governs what ListenUp SENDS only; reads logged on
 * Hardcover still come back either way. The wire names are pinned: the server stores them verbatim.
 */
@Serializable
enum class HardcoverShareMode {
    /**
     * The default, and the behaviour before the choice existed: after a real listen a book goes to
     * Currently Reading, its progress follows, and finishing it marks it Read.
     */
    @SerialName("AS_I_LISTEN")
    AS_I_LISTEN,

    /**
     * Nothing reaches Hardcover until the listener finishes a book. The book then appears as Read, with
     * when they started and when they finished.
     */
    @SerialName("FINISHED_ONLY")
    FINISHED_ONLY,
}

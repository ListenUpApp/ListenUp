package com.calypsan.listenup.api.metadata

import kotlinx.serialization.Serializable

/**
 * A contributor field whose provenance is tracked — what a hand edit protects and a person match may fill.
 * Keys [com.calypsan.listenup.api.sync.ContributorSyncPayload.fieldProvenance] with the same [FieldProvenance]
 * value books use.
 */
@Serializable
enum class ContributorField {
    /** The display name. A person match never writes it. */
    NAME,

    /** The sort name. */
    SORT_NAME,

    /** The biography (the payload's `description`). */
    BIOGRAPHY,

    /** The photo (the payload's `imagePath`). */
    PHOTO,
}

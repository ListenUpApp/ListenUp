package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.metadata.BookField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What Apply writes for one field: what you have, or one reviewed option. Unticking a field is [KeepCurrent]. */
@Serializable
sealed interface FieldChoice {
    /** Leave the field as it is. */
    @Serializable
    @SerialName("FieldChoice.KeepCurrent")
    data object KeepCurrent : FieldChoice

    /** Write the reviewed option [optionId]. */
    @Serializable
    @SerialName("FieldChoice.Option")
    data class Option(
        @SerialName("optionId") val optionId: String,
    ) : FieldChoice
}

/** One field's decision in an Apply. A list of these, not a map, because enum-keyed maps don't bridge to Swift. */
@Serializable
@SerialName("FieldDecision")
data class FieldDecision(
    @SerialName("field") val field: BookField,
    @SerialName("choice") val choice: FieldChoice,
)

/** Which image Apply writes: the current one, or one reviewed candidate. Never "the match's default". */
@Serializable
sealed interface ImageChoice {
    /** Keep the current image. */
    @Serializable
    @SerialName("ImageChoice.KeepCurrent")
    data object KeepCurrent : ImageChoice

    /** Write the reviewed candidate [optionId]. */
    @Serializable
    @SerialName("ImageChoice.Candidate")
    data class Candidate(
        @SerialName("optionId") val optionId: String,
    ) : ImageChoice
}

/**
 * Labels (genres or moods) to [add] and to [remove]. Never replace-all: removing one of yours is explicit. Each
 * list is read as a set; an empty change leaves the labels alone.
 */
@Serializable
@SerialName("LabelSetChange")
data class LabelSetChange(
    @SerialName("add") val add: List<String> = emptyList(),
    @SerialName("remove") val remove: List<String> = emptyList(),
)

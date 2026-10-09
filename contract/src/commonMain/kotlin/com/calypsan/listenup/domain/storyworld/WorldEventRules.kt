package com.calypsan.listenup.domain.storyworld

import com.calypsan.listenup.api.dto.worldevent.WorldEventUpsert
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.SeriesId

/**
 * The one set of rules for what a Story World event may say, shared by client and server (the
 * `ReadingOrderName` precedent) so an offline refusal reads exactly like an online one. The server applies
 * every rule inside the write's transaction; the client applies them before queuing, so a write the server
 * would refuse never reaches the outbox.
 *
 * What the rules can't see — whether an entity exists in the event's world, whether an anchor book belongs
 * to it — the server checks against its database (`WorldEventIntegrity`).
 */
object WorldEventRules {
    /** Longest event text, in UTF-16 chars, after trimming. */
    const val MAX_TEXT: Int = 4_000

    /** Longest detail (a JOINS role, a LEAVES "how"), after trimming. */
    const val MAX_DETAIL: Int = 100

    /** Most ops one [com.calypsan.listenup.api.dto.worldevent.EventsBatch] may carry. */
    const val MAX_BATCH: Int = 500

    /** Why an event can't live at this home (exactly one of series or book), or null. */
    fun homeProblem(
        homeSeriesId: SeriesId?,
        homeBookId: BookId?,
    ): ValidationError? =
        if ((homeSeriesId == null) == (homeBookId == null)) {
            ValidationError(message = "An event belongs to exactly one series or book.")
        } else {
            null
        }

    /** Why a new event can't have [type]: only [WorldEventType.UNKNOWN] can't. */
    fun creationProblem(type: WorldEventType): ValidationError? =
        if (type == WorldEventType.UNKNOWN) {
            ValidationError(message = "Choose what kind of event this is.", field = "type")
        } else {
            null
        }

    /**
     * Why [upsert] can't be written as an event of [type] (the stored type, when the upsert carries
     * [WorldEventType.UNKNOWN] on an edit), or null. Shape rules always apply; the type's own rules apply
     * only to a type this build knows.
     */
    fun contentProblem(
        upsert: WorldEventUpsert,
        type: WorldEventType = upsert.type,
    ): ValidationError? = shapeProblem(upsert) ?: typeProblem(upsert, type)

    /**
     * Why entities of [subjectKind] and [objectKind] can't fill [type]'s parts, or null. A null kind is an
     * empty part, which [contentProblem] judges.
     */
    fun kindProblem(
        type: WorldEventType,
        subjectKind: EntityKind?,
        objectKind: EntityKind?,
    ): WorldEventError.WrongEntityKind? {
        val roles = rolesOf(type)
        val fits = (subjectKind == null || roles.subject.accepts(subjectKind)) && (objectKind == null || roles.obj.accepts(objectKind))
        return if (fits) null else WorldEventError.WrongEntityKind(debugInfo = "$type: subject=$subjectKind object=$objectKind")
    }

    private fun shapeProblem(upsert: WorldEventUpsert): ValidationError? =
        when {
            (upsert.bookId == null) != (upsert.positionMs == null) -> {
                ValidationError(message = "Pin an event to a book and a moment together, or to neither.", field = "positionMs")
            }

            (upsert.positionMs ?: 0L) < 0L -> {
                ValidationError(message = "A moment can't come before the start of its book.", field = "positionMs")
            }

            upsert.text.trim().length > MAX_TEXT -> {
                ValidationError(message = "Keep the text to $MAX_TEXT characters.", field = "text")
            }

            (upsert.detail?.trim()?.length ?: 0) > MAX_DETAIL -> {
                ValidationError(message = "Keep the detail to $MAX_DETAIL characters.", field = "detail")
            }

            else -> {
                null
            }
        }

    private fun typeProblem(
        upsert: WorldEventUpsert,
        type: WorldEventType,
    ): ValidationError? {
        if (type == WorldEventType.UNKNOWN) return null
        val roles = rolesOf(type)
        return when {
            roles.textRequired && upsert.text.isBlank() -> {
                ValidationError(message = "Write what happens.", field = "text")
            }

            !upsert.detail.isNullOrBlank() && !roles.takesDetail -> {
                ValidationError(message = "Only joining or leaving takes a detail.", field = "detail")
            }

            roles.subject.required && upsert.subjectEntityId == null -> {
                ValidationError(message = "Choose who or what this event is about.", field = "subjectEntityId")
            }

            roles.obj.required && upsert.objectEntityId == null -> {
                ValidationError(message = "Choose the other side of this event.", field = "objectEntityId")
            }

            else -> {
                null
            }
        }
    }

    /** One part of an event: whether it must be filled, and by which kinds (null = any). */
    private class Part(
        val required: Boolean,
        val kinds: Set<EntityKind>?,
    ) {
        fun accepts(kind: EntityKind): Boolean = kinds == null || kind in kinds
    }

    /** What a type asks of its parts, its text and its detail. */
    private class Roles(
        val subject: Part,
        val obj: Part,
        val textRequired: Boolean = false,
        val takesDetail: Boolean = false,
    )

    private val ANY = Part(required = false, kinds = null)
    private val SOMEONE = Part(required = true, kinds = null)
    private val A_PLACE = Part(required = false, kinds = setOf(EntityKind.LOCATION))
    private val THE_PLACE = Part(required = true, kinds = setOf(EntityKind.LOCATION))
    private val A_CHARACTER = Part(required = true, kinds = setOf(EntityKind.CHARACTER))
    private val THE_GROUP = Part(required = true, kinds = setOf(EntityKind.GROUP))
    private val THE_PEOPLE = Part(required = true, kinds = setOf(EntityKind.PEOPLE))

    private fun rolesOf(type: WorldEventType): Roles =
        when (type) {
            WorldEventType.NOTE -> Roles(ANY, ANY, textRequired = true)
            WorldEventType.ENTERS_SCENE, WorldEventType.EXITS_SCENE, WorldEventType.DEPARTS -> Roles(SOMEONE, A_PLACE)
            WorldEventType.MOVES_TO -> Roles(SOMEONE, THE_PLACE)
            WorldEventType.JOINS, WorldEventType.LEAVES -> Roles(A_CHARACTER, THE_GROUP, takesDetail = true)
            WorldEventType.BELONGS_TO -> Roles(A_CHARACTER, THE_PEOPLE)
            WorldEventType.ALIAS,
            WorldEventType.BORN,
            WorldEventType.DIES,
            WorldEventType.ITEM_TRANSFER,
            WorldEventType.RELATIONSHIP_CHANGE,
            WorldEventType.UNKNOWN,
            -> Roles(ANY, ANY)
        }
}

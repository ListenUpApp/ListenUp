package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** The section of the admin permissions screen a [Permission] sits under. */
enum class PermissionGroup {
    /** Book and catalogue metadata: editing it, and merging or deleting catalogue entries. */
    LIBRARY,

    /** Story World, the encyclopedia of a series or book: adding to it, and merging its entries. */
    STORY_WORLD,

    /** Reading orders: making named orders within a series, and editing the ones you made. */
    READING_ORDERS,

    /** The group of [Permission.UNKNOWN]. Never rendered. */
    UNKNOWN,
}

/**
 * One thing a MEMBER may be allowed to do. ROOT and ADMIN hold every permission; a MEMBER holds exactly
 * the ones their [UserPermissions] flags grant, on the books they can see.
 *
 * **The defaults rule.** Additive, undoable work defaults on; destructive or library-wide work defaults
 * off. [defaultGranted] records the rule per entry, and the client's Contributor preset is exactly these
 * defaults, so a new permission lands in the right preset by declaring its default here.
 *
 * [wireKey] is the [UserPermissions] field's serial name, and the key a server lists in
 * [com.calypsan.listenup.api.dto.ServerInfo.permissionFlags] when it enforces this permission.
 *
 * **Adding a permission** is one entry here, one field on [UserPermissions] and [UserPermissionsPatch],
 * one branch in [allows], a column, and a toggle under its [group].
 *
 * Serialized by name through [PermissionSerializer], not the compiler-generated enum serializer:
 * `contractJson`'s `coerceInputValues` never reaches an enum decoded standalone, so a string-backed
 * serializer that falls back to [UNKNOWN] keeps one newer permission from failing an older client's
 * decode — the `ExternalRatingSource` precedent.
 *
 * @property group where the toggle sits.
 * @property wireKey the [UserPermissions] serial name, and this permission's `permissionFlags` key.
 * @property defaultGranted whether a new member holds it, per the defaults rule.
 */
@Serializable(with = PermissionSerializer::class)
enum class Permission(
    val group: PermissionGroup,
    val wireKey: String,
    val defaultGranted: Boolean,
) {
    /**
     * Book fields, covers, contributors/series/genres on a book, tier labels, chapters, metadata apply
     * and refresh, book and person matching (find, review, apply, undo), and editing — not merging or
     * deleting — contributors, series, genres, tags and moods. Additive and undoable, so on by default.
     */
    EDIT_METADATA(PermissionGroup.LIBRARY, wireKey = "canEdit", defaultGranted = true),

    /**
     * Merge, unmerge and delete contributors, series, genres, tags and moods, and undo those merges.
     * Library-wide, and deletes cannot be undone, so off by default.
     */
    CURATE_LIBRARY(PermissionGroup.LIBRARY, wireKey = "canCurateLibrary", defaultGranted = false),

    /**
     * Create, edit and delete Story World entries, and revert any change but a merge. History keeps every
     * change and any of them can be undone, so on by default.
     */
    CONTRIBUTE_STORY_WORLD(PermissionGroup.STORY_WORLD, wireKey = "canContributeStoryWorld", defaultGranted = true),

    /** Merge Story World entries, and revert a merge. Merging rewrites a whole world, so off by default. */
    CURATE_STORY_WORLD(PermissionGroup.STORY_WORLD, wireKey = "canCurateStoryWorld", defaultGranted = false),

    /**
     * Make reading orders within a series, and rename, reorder, add to, remove from and delete the ones
     * you made. Additive and undoable, so on by default. Following an order needs no permission.
     */
    MAKE_READING_ORDERS(PermissionGroup.READING_ORDERS, wireKey = "canMakeReadingOrders", defaultGranted = true),

    /** A permission this build does not know (a newer server's). Grants nothing and never renders. */
    UNKNOWN(PermissionGroup.UNKNOWN, wireKey = "", defaultGranted = false),
    ;

    /** Lookups over the permissions this build knows. */
    companion object {
        /** Every permission this build knows, in screen order. */
        val known: List<Permission> = entries.filterNot { it == UNKNOWN }

        /** The permission whose [wireKey] is [key], or [UNKNOWN]. */
        fun fromWireKey(key: String): Permission = known.firstOrNull { it.wireKey == key } ?: UNKNOWN
    }
}

/**
 * Decodes [Permission] by name, falling back to [Permission.UNKNOWN] for a name this build has never
 * heard of (a permission added by a newer server).
 */
object PermissionSerializer : KSerializer<Permission> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Permission", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Permission,
    ) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): Permission {
        val name = decoder.decodeString()
        return Permission.entries.firstOrNull { it.name == name } ?: Permission.UNKNOWN
    }
}

package com.calypsan.listenup.client.domain.model

/**
 * Which of a server's members cannot see a book, as an admin's device derives it from the
 * collection memberships, collection shares and user roster it already syncs.
 *
 * Framed as the exception rather than the rule: most books are [Public] and the UI shows nothing
 * for them. Only an admin's device can compute this. A member never syncs the All Books
 * membership or the roster, so on their device it is always absent rather than wrong.
 */
sealed interface BookVisibility {
    /** In All Books only. Every member can see it. */
    data object Public : BookVisibility

    /**
     * In at least one normal (non-system) collection, so only those collections' owners and the
     * members they are shared with can see it.
     *
     * @property collections The normal collections holding the book, sorted by name.
     * @property hiddenFrom Which members cannot see it.
     */
    data class Restricted(
        val collections: List<CollectionRef>,
        val hiddenFrom: HiddenFrom,
    ) : BookVisibility

    /**
     * Held in the inbox for review, so hidden from every member until an admin releases it.
     * Decided by the same held fragment the Admin Inbox reads, so the two features never disagree;
     * Book Detail's held section (the inbox feature) renders it, and the visibility section stays out.
     */
    data object Held : BookVisibility

    /**
     * In no live collection at all, so hidden from every member, with nothing that will put it
     * back. Usually the residue of an inbox release that failed partway.
     */
    data object Stranded : BookVisibility
}

/**
 * The members a [BookVisibility.Restricted] book is hidden from. Admins never appear here,
 * because they can see every book.
 */
sealed interface HiddenFrom {
    /** Every member owns, or is shared into, at least one of the book's collections. */
    data object Nobody : HiddenFrom

    /** No member can see it — for example, its only collection is shared with no one. */
    data object Everyone : HiddenFrom

    /**
     * Some members cannot see it.
     *
     * @property names Their display names, sorted case-insensitively.
     */
    data class Members(
        val names: List<String>,
    ) : HiddenFrom
}

/**
 * Just enough of a collection to name it and to open it.
 *
 * @property id The collection's id.
 * @property name The collection's display name.
 */
data class CollectionRef(
    val id: String,
    val name: String,
)

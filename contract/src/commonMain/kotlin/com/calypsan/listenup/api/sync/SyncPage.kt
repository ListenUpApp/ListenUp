package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One page of catch-up rows for a single sync domain.
 *
 * The envelope is typed; the rows are not, and that asymmetry is deliberate. Sync domains are
 * data-driven — repositories self-register in a runtime registry — so the server holds a
 * type-erased page and can only encode it through the concrete `KSerializer<T>` its repository
 * supplies. The client decodes each item with the same serializer, reached through its own
 * per-domain handler.
 *
 * **This is not a hole in the typed-wire guarantee.** Both ends name the *same* commonMain
 * `@Serializable` class, so a renamed field still breaks both sides at compile time and
 * `@SerialName` still pins the wire name. The only thing the compiler cannot check is the
 * *association* — that [domain] selects the same type at both ends — and that association is
 * dynamic by design. `SyncDomainCompletenessSpec` pins registry parity and
 * `SyncDomainRoundTripSpec` pins encode/decode per domain, so the check moves from compile time
 * to CI rather than disappearing.
 *
 * The rule that licenses this: *a payload may cross as encoded text only when the concrete type
 * is selected by runtime data, never by code path.* Where the type IS statically known — the
 * digest, the domain list — the contract stays typed. See [SyncFrame], which carries the
 * firehose body the same way for the same reason.
 *
 * Items are carried one-encoded-string-each rather than as a single encoded page so that the
 * client never materialises a whole-page JSON tree alongside the decoded rows (the shape that
 * drove first-sync memory spikes), and so one malformed row fails alone.
 */
@Serializable
data class SyncPage(
    /** The domain these rows belong to — the key that selects the payload serializer. */
    @SerialName("domain")
    val domain: String,
    /** Each element is one domain row, encoded with that domain's payload serializer. */
    @SerialName("items")
    val items: List<String>,
    /** Highest revision in this page; null when [items] is empty. */
    @SerialName("nextCursor")
    val nextCursor: Long?,
    /** Whether rows remain beyond this page. Clients page until this is false. */
    @SerialName("hasMore")
    val hasMore: Boolean,
)

/**
 * Which column a targeted fetch matches on.
 *
 * A contract enum rather than a column name: the REST route this replaced took the column as a
 * request parameter guarded by a server-side allowlist, and porting that shape verbatim would
 * have promoted a storage identifier to permanent wire API. The server maps each case to its
 * column and keeps the per-domain allowlist.
 */
@Serializable
enum class TargetedMatch {
    /** The row's own id — books, collections. */
    @SerialName("id")
    ID,

    /** The row's `collection_id` — collection membership rows. */
    @SerialName("collection_id")
    COLLECTION_ID,

    /** The row's `book_id` — activities. */
    @SerialName("book_id")
    BOOK_ID,
}

package com.calypsan.listenup.server.metadata.spi

/**
 * The set of registered metadata providers, queryable by capability and by id.
 *
 * A pure data structure — it holds providers and answers "who can do X?" and "give
 * me provider Y as an X". The enrichment router leans on [capable] to fan a domain
 * lookup across every provider that supports it, and on [get] to consult a specific
 * provider named in a precedence chain. No providers are wired in yet (that lands
 * with the concrete provider re-skin in a later step); step 1 defines the shape and
 * proves the capability queries.
 *
 * When two providers share an [MetadataProviderId] the last one wins in [byId] — a
 * provider is expected to be a single object per id, so this only guards against
 * accidental double-registration.
 */
class MetadataProviderRegistry(
    /** The registered providers, each a single object implementing one or more capabilities. */
    val providers: List<MetadataCapability>,
) {
    /** Providers indexed by their [MetadataProviderId]. */
    val byId: Map<MetadataProviderId, MetadataCapability> = providers.associateBy { it.id }

    /** Every registered provider that implements capability [C], in registration order. */
    inline fun <reified C : MetadataCapability> capable(): List<C> = providers.filterIsInstance<C>()

    /** The provider registered under [id] as capability [C], or `null` if absent or incapable. */
    inline fun <reified C : MetadataCapability> get(id: MetadataProviderId): C? = byId[id] as? C
}

package com.calypsan.listenup.api.metadata

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The forward-compatible codec for a [BookField]-keyed provenance map.
 *
 * A `Map<Enum, _>` encodes to a JSON object whose KEYS are the enum names, and on decode each key
 * goes through the enum's serializer — where an unrecognised name throws. `ignoreUnknownKeys`
 * governs unknown *properties of a class*, and `coerceInputValues` governs a *property with a
 * default*; neither reaches a map key. So without this serializer, one new [BookField] on a newer
 * server made every book payload carrying provenance for it undecodable on every older client —
 * freezing the books sync domain — and made a downgraded server unable to read its own column.
 *
 * Decoding therefore goes through `Map<String, FieldProvenance>` and drops the entries whose key
 * this build does not recognise. Encoding is unchanged (every key is a known member by
 * construction), so the stored and wire formats are byte-identical to the plain enum-keyed codec
 * this replaced.
 */
public object FieldProvenanceMapSerializer :
    KSerializer<Map<BookField, FieldProvenance>> by EnumKeyedProvenanceSerializer(BookField.entries)

/** The same forward-compatible codec for a [ContributorField]-keyed provenance map. */
public object ContributorFieldProvenanceMapSerializer :
    KSerializer<Map<ContributorField, FieldProvenance>> by EnumKeyedProvenanceSerializer(ContributorField.entries)

/**
 * A provenance map keyed by an enum, encoded with the enum names as JSON keys; on decode a name this build
 * doesn't recognise is dropped rather than thrown on. See [FieldProvenanceMapSerializer].
 */
class EnumKeyedProvenanceSerializer<E : Enum<E>>(
    entries: List<E>,
) : KSerializer<Map<E, FieldProvenance>> {
    private val delegate = MapSerializer(String.serializer(), FieldProvenance.serializer())
    private val byName = entries.associateBy { it.name }

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<E, FieldProvenance>,
    ) {
        delegate.serialize(encoder, value.mapKeys { (field, _) -> field.name })
    }

    override fun deserialize(decoder: Decoder): Map<E, FieldProvenance> =
        delegate
            .deserialize(decoder)
            .mapNotNull { (name, provenance) -> byName[name]?.let { it to provenance } }
            .toMap()
}

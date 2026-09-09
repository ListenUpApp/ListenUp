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
public object FieldProvenanceMapSerializer : KSerializer<Map<BookField, FieldProvenance>> {
    private val delegate = MapSerializer(String.serializer(), FieldProvenance.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<BookField, FieldProvenance>,
    ) {
        delegate.serialize(encoder, value.mapKeys { (field, _) -> field.name })
    }

    override fun deserialize(decoder: Decoder): Map<BookField, FieldProvenance> {
        val byName = BookField.entries.associateBy { it.name }
        return delegate
            .deserialize(decoder)
            .mapNotNull { (name, provenance) -> byName[name]?.let { it to provenance } }
            .toMap()
    }
}

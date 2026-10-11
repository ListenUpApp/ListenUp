package com.calypsan.listenup.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Encodes an enum as its entry name and decodes a name this build has never heard of as [fallback]
 * instead of throwing.
 *
 * `coerceInputValues` only substitutes a class property's declared default. It never reaches an enum
 * that is a list element, or one decoded standalone as an RPC result, so those enums carry a
 * serializer of their own: an `object` beside the enum that delegates here. The bytes written are
 * identical to the compiler-generated serializer's, as long as no entry renames itself with
 * `@SerialName`.
 */
internal class LenientEnumSerializer<E : Enum<E>>(
    serialName: String,
    entries: List<E>,
    private val fallback: E,
) : KSerializer<E> {
    private val byName = entries.associateBy { it.name }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: E,
    ) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): E = byName[decoder.decodeString()] ?: fallback
}

/**
 * A map keyed by an enum, encoded with the entry names as JSON keys; on decode an entry whose key
 * this build does not recognise is dropped rather than thrown on.
 *
 * Dropping, not falling back: two unknown keys folded onto one fallback member would overwrite each
 * other, and a count or label filed under the wrong key is worse than one that is absent.
 */
internal class EnumKeyedMapSerializer<E : Enum<E>, V>(
    entries: List<E>,
    valueSerializer: KSerializer<V>,
) : KSerializer<Map<E, V>> {
    private val delegate = MapSerializer(String.serializer(), valueSerializer)
    private val byName = entries.associateBy { it.name }

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<E, V>,
    ) {
        delegate.serialize(encoder, value.mapKeys { (key, _) -> key.name })
    }

    override fun deserialize(decoder: Decoder): Map<E, V> =
        delegate
            .deserialize(decoder)
            .mapNotNull { (name, value) -> byName[name]?.let { it to value } }
            .toMap()
}

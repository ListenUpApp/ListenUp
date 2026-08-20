package com.calypsan.listenup.api.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * A leading number, optionally fractional: `1`, `06`, `1.5`. Anchored at the start, so it reads a
 * numeric *prefix* rather than requiring the whole string to be a number — that is what lets an
 * omnibus label like `"1-3"` file at book 1 instead of being thrown away.
 */
private val LEADING_NUMBER = Regex("""^\d+(\.\d+)?""")

/**
 * Parses a free-form series-sequence label into the number the model stores, or `null` when the
 * label carries no usable number.
 *
 * Series text arrives free-form and stays that way at the edge — audio tags, scanner output and
 * metadata-provider responses all keep their `String?`, because that is what the source literally
 * said. This is the single point where that text becomes the `Double?` the database holds, and it
 * is called from every persist path so the conversion cannot vary by route.
 *
 * **This must agree with `V62__series_sequence_numeric.sql`.** The migration converts the text
 * already in the database; this converts the text arriving after it. If the two disagree, a rescan
 * silently overwrites a value the migration preserved. `SeriesSequenceTest` is what holds them
 * together, case by case.
 *
 * Two rules, both inherited from that SQL:
 *
 * - **A leading numeric prefix wins.** `"1-3"` and `"1 Parts 1-2"` become `1.0`, matching SQLite's
 *   `CAST`. A plain [String.toDoubleOrNull] returns `null` for both — and using it is precisely the
 *   bug this replaces, because the old save path discarded those labels without saying so.
 * - **Anything not starting with a digit is refused, never coerced.** A bare `CAST` turns
 *   `"Prequel"` into `0.0`, filing an unnumbered volume as book 0 — ahead of book 1 in every list.
 *   A wrong number is worse than no number, so this returns `null` instead.
 */
fun parseSeriesSequence(label: String?): Double? {
    val trimmed = label?.trim().orEmpty()
    if (trimmed.isEmpty() || !trimmed.first().isDigit()) return null
    return LEADING_NUMBER.find(trimmed)?.value?.toDoubleOrNull()
}

/**
 * Reads [BookSeriesPayload.sequence] from a peer that may still be sending the old string form.
 *
 * A tolerant reader, for the same reason `contractJson` installs one on the polymorphic axis: a
 * client in the field must not break the first time it meets a peer of a different vintage.
 *
 * The concrete hazard is specific and one-directional. `isLenient` already bridges the easy cases —
 * a new server's `1.5` reads into an old client's `String?`, and an old server's `"1.5"` reads into
 * a new client's `Double?`. What it cannot do is parse `"1-3"` as a number, and an omnibus label is
 * exactly the kind of value the old text column was holding.
 *
 * That combination is not exotic, it is the normal upgrade order for a self-hosted app: the phone
 * updates itself from Play while the server waits to be updated by hand. And it is not a cosmetic
 * failure — the cursored pull decodes a page with `items.map { decodeFromString(...) }` and no
 * per-row catch, so one unparseable label throws out of the whole page, the cursor never advances,
 * and the books domain retries that same page forever. One bad row would stall the sync.
 *
 * So a string is accepted and run through [parseSeriesSequence] — the same rule the server's ingest
 * path and both database migrations use, so a value means the same thing whichever door it enters.
 */
public object SeriesSequenceSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.calypsan.listenup.api.sync.SeriesSequence", PrimitiveKind.DOUBLE).nullable

    override fun serialize(
        encoder: Encoder,
        value: Double?,
    ) {
        // Always written as a number. Tolerance is a reading concern; emitting the legacy shape too
        // would keep the ambiguity alive on the wire forever.
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }

    override fun deserialize(decoder: Decoder): Double? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        // isString is the version discriminator: a new peer sends a bare number, an old one a
        // quoted label. Both are answered; neither is an error.
        return if (primitive.isString) parseSeriesSequence(primitive.content) else primitive.doubleOrNull
    }
}

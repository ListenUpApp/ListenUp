package com.calypsan.listenup.server.scanner.sidecar.xml

/**
 * Parses [input] into a small DOM rooted at one [XmlElement].
 *
 * Deliberately minimal — covers the tiny XML surface `.nfo`/`.opf` sidecars use: nested elements,
 * text, attributes (both quote styles), CDATA, predefined + numeric char entities. It is XXE-safe
 * by construction: DOCTYPE declarations are skipped wholesale and entities are never expanded
 * beyond the five predefined names and numeric character references. Malformed structure throws.
 */
internal fun parseXml(input: String): XmlElement {
    val reader = MiniXmlReader(input)
    reader.skipMisc()
    val root = reader.parseElement()
    reader.skipMisc()
    return root
}

/** Single-pass recursive-descent reader over the raw string with a position cursor. */
private class MiniXmlReader(
    private val s: String,
) {
    private var pos = 0

    /** Skips inter-element "misc": whitespace, `<?xml … ?>`, `<!-- … -->`, and `<!DOCTYPE … >`. */
    fun skipMisc() {
        while (pos < s.length) {
            val c = s[pos]
            when {
                c.isWhitespace() -> pos++
                s.startsWith("<?", pos) -> skipUntil("?>")
                s.startsWith("<!--", pos) -> skipUntil("-->")
                s.startsWith("<!DOCTYPE", pos) -> skipDoctype()
                else -> return
            }
        }
    }

    /** Parses one element at the cursor (which must sit on its opening `<`). */
    fun parseElement(): XmlElement {
        expect('<')
        val tag = readName()
        val attributes = readAttributes()
        if (peek() == '/') {
            pos++
            expect('>')
            return XmlElement(tag, attributes, emptyList())
        }
        expect('>')
        val children = readChildren(tag)
        return XmlElement(tag, attributes, children)
    }

    /** Reads children until the matching `</tag>`, which it consumes. */
    private fun readChildren(tag: String): List<XmlNode> {
        val children = mutableListOf<XmlNode>()
        while (true) {
            if (pos >= s.length) error("Unexpected EOF inside <$tag>")
            if (s.startsWith("</", pos)) {
                pos += 2
                val close = readName()
                if (close != tag) error("Mismatched close tag: expected </$tag>, got </$close>")
                skipSpace()
                expect('>')
                return children
            }
            if (s.startsWith("<![CDATA[", pos)) {
                children.add(XmlText(readCdata()))
            } else if (s.startsWith("<!--", pos)) {
                skipUntil("-->")
            } else if (peek() == '<') {
                children.add(parseElement())
            } else {
                children.add(XmlText(readText()))
            }
        }
    }

    /** Reads raw character data up to the next `<`, decoding entities. */
    private fun readText(): String {
        val start = pos
        while (pos < s.length && s[pos] != '<') pos++
        return decodeEntities(s.substring(start, pos))
    }

    /** Reads a `<![CDATA[ … ]]>` block, returning its literal (non-decoded) content. */
    private fun readCdata(): String {
        pos += "<![CDATA[".length
        val end = s.indexOf("]]>", pos)
        if (end < 0) error("Unterminated CDATA section")
        val content = s.substring(pos, end)
        pos = end + "]]>".length
        return content
    }

    /** Reads zero or more `name = "value"` / `name = 'value'` attributes, decoding entity values. */
    private fun readAttributes(): Map<String, String> {
        val attributes = LinkedHashMap<String, String>()
        while (true) {
            skipSpace()
            val c = peek()
            if (c == '>' || c == '/' || c == NUL) return attributes
            val name = readName()
            skipSpace()
            expect('=')
            skipSpace()
            attributes[name] = readQuoted()
        }
    }

    /** Reads a single- or double-quoted attribute value, decoding entities. */
    private fun readQuoted(): String {
        val quote = peek()
        if (quote != '"' && quote != '\'') error("Expected quoted attribute value at $pos")
        pos++
        val start = pos
        while (pos < s.length && s[pos] != quote) pos++
        if (pos >= s.length) error("Unterminated attribute value")
        val raw = s.substring(start, pos)
        pos++
        return decodeEntities(raw)
    }

    /** Reads a tag/attribute name; namespace-agnostic so `dc:title` stays one literal name. */
    private fun readName(): String {
        val start = pos
        while (pos < s.length && isNameChar(s[pos])) pos++
        if (pos == start) error("Expected a name at $pos")
        return s.substring(start, pos)
    }

    /** Skips a DOCTYPE without ever expanding entities or external refs (the XXE guard). */
    private fun skipDoctype() {
        val subset = s.indexOf('[', pos)
        val end = s.indexOf('>', pos)
        if (end < 0) error("Unterminated DOCTYPE")
        if (subset in (pos + 1) until end) {
            val close = s.indexOf(']', subset)
            if (close < 0) error("Unterminated DOCTYPE internal subset")
            val tail = s.indexOf('>', close)
            if (tail < 0) error("Unterminated DOCTYPE")
            pos = tail + 1
        } else {
            pos = end + 1
        }
    }

    private fun skipUntil(marker: String) {
        val end = s.indexOf(marker, pos)
        if (end < 0) error("Unterminated '$marker'")
        pos = end + marker.length
    }

    private fun skipSpace() {
        while (pos < s.length && s[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < s.length) s[pos] else NUL

    private fun expect(c: Char) {
        if (pos >= s.length || s[pos] != c) error("Expected '$c' at $pos")
        pos++
    }

    private companion object {
        const val NUL = '\u0000'

        fun isNameChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == ':'
    }
}

/**
 * Decodes the five predefined entities and decimal/hex numeric char refs; unknown `&…;` pass through.
 *
 * Astral code points (above U+FFFF) become a surrogate pair; out-of-range or invalid numeric refs
 * (negative, lone surrogate, or above U+10FFFF) pass through literally rather than corrupting.
 */
private fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                append(c)
                i++
                continue
            }
            val semi = text.indexOf(';', i)
            if (semi < 0) {
                append(c)
                i++
                continue
            }
            val entity = text.substring(i + 1, semi)
            val decoded = decodeEntity(entity)
            if (decoded == null) {
                append(c)
                i++
            } else {
                append(decoded)
                i = semi + 1
            }
        }
    }
}

/** Resolves a single entity body (without `&`/`;`), or null when unrecognized. */
private fun decodeEntity(entity: String): String? =
    when (entity) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else ->
            when {
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    codePointToString(entity.substring(2).toIntOrNull(16))
                entity.startsWith("#") ->
                    codePointToString(entity.substring(1).toIntOrNull())
                else -> null
            }
    }

/** Code point → String: BMP char, a surrogate pair for astral planes, or null if out of range / invalid. */
private fun codePointToString(cp: Int?): String? =
    when {
        cp == null -> null
        cp in 0x0000..0xD7FF || cp in 0xE000..0xFFFF -> cp.toChar().toString()
        cp in 0x10000..0x10FFFF -> {
            val v = cp - 0x10000
            val high = (0xD800 + (v shr 10)).toChar()
            val low = (0xDC00 + (v and 0x3FF)).toChar()
            charArrayOf(high, low).concatToString()
        }
        else -> null // negative, lone surrogate (D800..DFFF), or > U+10FFFF → pass &#…; through literally
    }

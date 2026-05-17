package com.calypsan.listenup.server.scanner.sidecar

import io.github.oshai.kotlinlogging.KotlinLogging
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private val logger = KotlinLogging.logger {}

/**
 * A non-validating, XXE-hardened [DocumentBuilderFactory] shared by the XML
 * sidecar parsers ([NfoParser], [OpfParser]). Sidecar files are untrusted
 * library content, so external DTD/entity resolution is disabled.
 *
 * Non-namespace-aware by design: sidecar parsers query elements by their
 * literal prefixed tag name (e.g. `dc:title`), which only works when the
 * parser does not split prefix from local name.
 *
 * Each feature toggle is applied defensively — a JDK parser that rejects a
 * given feature must not abort the parse — and the caller's parse-level catch
 * is the final safety net regardless.
 */
internal fun hardenedDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        isExpandEntityReferences = false
        trySetFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        trySetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        trySetFeature("http://xml.org/sax/features/external-general-entities", false)
        trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }

/** Sets [feature], swallowing parsers that don't recognise it — hardening must never crash the parse. */
private fun DocumentBuilderFactory.trySetFeature(
    feature: String,
    value: Boolean,
) {
    try {
        setFeature(feature, value)
    } catch (e: Exception) {
        logger.debug(e) { "XML parser does not support feature '$feature' — continuing" }
    }
}

package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.embeddedmeta.format.mp4.Mp4Parser
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Koin module for the embeddedmeta parser package.
 *
 * Registers:
 * - [AudioFormatDetector] — magic-byte sniffer
 * - [Mp3Parser], [Mp4Parser] — today's [AudioFormatParser] implementations
 * - `List<AudioFormatParser>` — the registry the entry point dispatches over
 * - [EmbeddedMetadataParser] — public entry point
 *
 * Adding a new audio format means adding the parser class + registering it
 * here (one line). The dispatcher and the public entry-point signature
 * don't change — see `AudioFormatParser` KDoc.
 */
internal val embeddedmetaModule =
    module {
        singleOf(::AudioFormatDetector)
        singleOf(::Mp3Parser)
        singleOf(::Mp4Parser)
        single<List<AudioFormatParser>> { listOf(get<Mp3Parser>(), get<Mp4Parser>()) }
        // Explicit factory — `singleOf` would try to resolve EmbeddedMetadataParser's
        // sourceFactory lambda parameter from the graph; the default value handles it.
        single { EmbeddedMetadataParser(detector = get(), parsers = get()) }
    }

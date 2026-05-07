package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.embeddedmeta.format.mp4.Mp4Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.inject

class EmbeddedmetaModuleTest :
    FunSpec({
        afterTest { stopKoin() }

        test("embeddedmetaModule resolves the entry point with both parsers wired") {
            startKoin { modules(embeddedmetaModule) }

            val koin =
                object : KoinTest {
                    val parser by inject<EmbeddedMetadataParser>()
                    val parsers by inject<List<AudioFormatParser>>()
                    val mp3 by inject<Mp3Parser>()
                    val mp4 by inject<Mp4Parser>()
                }

            koin.parser shouldNotBe null
            koin.parsers shouldHaveSize 2
            koin.mp3.shouldBeInstanceOf<Mp3Parser>()
            koin.mp4.shouldBeInstanceOf<Mp4Parser>()
            koin.parsers.shouldHaveSize(2)
        }
    })

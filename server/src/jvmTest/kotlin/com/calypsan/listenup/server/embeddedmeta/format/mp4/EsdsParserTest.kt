package com.calypsan.listenup.server.embeddedmeta.format.mp4

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EsdsParserTest :
    FunSpec({
        fun descr(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte(), body.size.toByte()) + body
        fun intBE(v: Int) =
            byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

        fun esds(oti: Int, avgBitrate: Int, asc: ByteArray): ByteArray {
            val dsi = descr(0x05, asc)
            val dcdBody =
                byteArrayOf(oti.toByte(), 0x15) + // objectTypeIndication, streamType/upstream/reserved
                    byteArrayOf(0, 0, 0) + // bufferSizeDB(3)
                    intBE(0) + // maxBitrate
                    intBE(avgBitrate) +
                    dsi
            val dcd = descr(0x04, dcdBody)
            val esBody = byteArrayOf(0, 1, 0) + dcd // ES_ID(2)=1, flags(1)=0
            val es = descr(0x03, esBody)
            return byteArrayOf(0, 0, 0, 0) + es // FullBox version+flags
        }

        test("parses AAC esds: oti 0x40, avg bitrate, and the ASC bytes") {
            val asc = byteArrayOf(0b00010_001.toByte(), 0b0_0010_000.toByte())
            val info = parseEsds(esds(oti = 0x40, avgBitrate = 128000, asc = asc))
            info.objectTypeIndication shouldBe 0x40
            info.avgBitrate shouldBe 128000
            info.audioSpecificConfig!!.size shouldBe asc.size
            info.audioSpecificConfig!![0] shouldBe asc[0]
        }
        test("returns null ASC when DecoderSpecificInfo absent") {
            val info = parseEsds(byteArrayOf(0, 0, 0, 0, 0x03, 5, 0, 1, 0, 0x04, 0))
            info.audioSpecificConfig shouldBe null
        }
        test("returns nulls for non-esds garbage") {
            val info = parseEsds(byteArrayOf(0, 0, 0, 0, 0x99.toByte()))
            info.objectTypeIndication shouldBe null
        }
    })

package com.calypsan.listenup.client.device

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** [deviceGlyphFor] — the one mapping from a session's `deviceType` string to the icon every platform draws. */
class DeviceGlyphTest :
    FunSpec({
        test("every device type the app reports maps to a glyph of its own") {
            DeviceType.entries.forEach { type ->
                deviceGlyphFor(type.wireName) shouldNotBe DeviceGlyph.Unknown
            }
            DeviceType.entries
                .map { deviceGlyphFor(it.wireName) }
                .toSet()
                .size shouldBe DeviceType.entries.size
        }

        test("each device type maps to its own kind of glyph") {
            deviceGlyphFor(DeviceType.Phone.wireName) shouldBe DeviceGlyph.Phone
            deviceGlyphFor(DeviceType.Tablet.wireName) shouldBe DeviceGlyph.Tablet
            deviceGlyphFor(DeviceType.Desktop.wireName) shouldBe DeviceGlyph.Computer
            deviceGlyphFor(DeviceType.Tv.wireName) shouldBe DeviceGlyph.Tv
            deviceGlyphFor(DeviceType.Watch.wireName) shouldBe DeviceGlyph.Watch
            deviceGlyphFor(DeviceType.Auto.wireName) shouldBe DeviceGlyph.Car
            deviceGlyphFor(DeviceType.Xr.wireName) shouldBe DeviceGlyph.Headset
        }

        test("the web client's browser sessions get the browser glyph") {
            deviceGlyphFor("browser") shouldBe DeviceGlyph.Browser
        }

        test("older and cast-like names keep their glyphs") {
            deviceGlyphFor("laptop") shouldBe DeviceGlyph.Computer
            deviceGlyphFor("cast") shouldBe DeviceGlyph.Cast
            deviceGlyphFor("speaker") shouldBe DeviceGlyph.Speaker
        }

        test("matching ignores case") {
            deviceGlyphFor("PHONE") shouldBe DeviceGlyph.Phone
            deviceGlyphFor("Tablet") shouldBe DeviceGlyph.Tablet
        }

        test("a missing or unrecognised type is Unknown") {
            deviceGlyphFor(null) shouldBe DeviceGlyph.Unknown
            deviceGlyphFor("toaster") shouldBe DeviceGlyph.Unknown
        }
    })

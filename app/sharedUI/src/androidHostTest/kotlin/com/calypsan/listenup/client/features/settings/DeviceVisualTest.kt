package com.calypsan.listenup.client.features.settings

import com.calypsan.listenup.client.device.DeviceGlyph
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** [deviceVisualFor] — every kind of device the Devices screen can list draws its own icon. */
class DeviceVisualTest :
    FunSpec({
        test("the kinds of device a person is likely to own each draw a distinct icon") {
            val owned =
                listOf(
                    DeviceGlyph.Phone,
                    DeviceGlyph.Tablet,
                    DeviceGlyph.Computer,
                    DeviceGlyph.Browser,
                    DeviceGlyph.Tv,
                    DeviceGlyph.Watch,
                )

            owned.map { deviceVisualFor(it).icon }.toSet().size shouldBe owned.size
        }

        test("every glyph resolves to an icon, and only an unknown device draws the generic one") {
            val generic = deviceVisualFor(DeviceGlyph.Unknown).icon

            DeviceGlyph.entries
                .filter { it != DeviceGlyph.Unknown }
                .forEach { glyph -> (deviceVisualFor(glyph).icon == generic) shouldBe false }
        }
    })

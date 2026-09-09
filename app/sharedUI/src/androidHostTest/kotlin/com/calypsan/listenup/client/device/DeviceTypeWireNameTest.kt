package com.calypsan.listenup.client.device

import com.calypsan.listenup.api.dto.auth.DEVICE_FIELD_MAX
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * Tests for [wireName] — the `DeviceInfo.deviceType` string an Android session reports.
 *
 * The bug that motivates this: every Android session was reported as `"phone"`, hard-coded in the
 * Koin `DeviceInfoProvider`, even though `DeviceContextProvider` already classified the device as
 * Tv / Auto / Watch / Tablet / Phone. The Devices settings screen therefore drew a phone glyph
 * next to the user's tablet and their car.
 *
 * These names are persisted in the server's `sessions.device_type` column — the server stores the
 * string opaquely and validates nothing — so renaming one silently reclassifies existing sessions.
 * That is what these per-value assertions are for.
 */
class DeviceTypeWireNameTest :
    FunSpec({

        test("Phone reports as phone") {
            DeviceType.Phone.wireName() shouldBe "phone"
        }

        test("Tablet reports as tablet") {
            DeviceType.Tablet.wireName() shouldBe "tablet"
        }

        test("Desktop reports as desktop") {
            DeviceType.Desktop.wireName() shouldBe "desktop"
        }

        test("Tv reports as tv") {
            DeviceType.Tv.wireName() shouldBe "tv"
        }

        test("Auto reports as auto") {
            DeviceType.Auto.wireName() shouldBe "auto"
        }

        test("Watch reports as watch") {
            DeviceType.Watch.wireName() shouldBe "watch"
        }

        test("Xr reports as xr") {
            DeviceType.Xr.wireName() shouldBe "xr"
        }

        test("every form factor gets a distinct name") {
            // A collision would silently merge two form factors in the Devices screen.
            DeviceType.entries.map { it.wireName() }.toSet() shouldHaveSize DeviceType.entries.size
        }

        test("every name is non-blank and lowercase") {
            // deviceVisualFor lowercases before matching, but the wire value is what the server
            // persists — emit it already normalised rather than relying on the reader.
            DeviceType.entries.forEach {
                val name = it.wireName()
                name.shouldNotBeBlank()
                name shouldBe name.lowercase()
            }
        }

        test("every name fits the contract's device-field cap") {
            // DeviceInfo's init require() THROWS above DEVICE_FIELD_MAX, so an over-long name
            // would crash login rather than degrade.
            DeviceType.entries.forEach {
                it.wireName().length shouldBeLessThanOrEqual DEVICE_FIELD_MAX
            }
        }

        test("the three names deviceVisualFor gives a specific glyph to are the ones we emit") {
            // DeviceVisual.kt:41-49 branches on "phone", "tablet", "desktop"/"laptop", "cast",
            // "speaker". These three are the form factors we can actually be; the rest fall
            // through to its generic device icon, which is honest.
            setOf(DeviceType.Phone, DeviceType.Tablet, DeviceType.Desktop)
                .map { it.wireName() }
                .toSet() shouldBe setOf("phone", "tablet", "desktop")
        }
    })

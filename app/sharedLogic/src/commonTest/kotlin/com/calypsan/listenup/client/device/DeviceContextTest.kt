package com.calypsan.listenup.client.device

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DeviceContextTest :
    FunSpec({
        test("phoneHasTouch") {
            DeviceContext(DeviceType.Phone).hasTouch shouldBe true
        }

        test("phoneCanEdit") {
            DeviceContext(DeviceType.Phone).canEdit shouldBe true
        }

        test("phoneSupportsFullLibrary") {
            DeviceContext(DeviceType.Phone).supportsFullLibrary shouldBe true
        }

        test("phoneHasNoDpad") {
            DeviceContext(DeviceType.Phone).hasDpad shouldBe false
        }

        test("phoneIsNotLeanback") {
            DeviceContext(DeviceType.Phone).isLeanback shouldBe false
        }

        test("tvIsLeanback") {
            val ctx = DeviceContext(DeviceType.Tv)
            ctx.isLeanback shouldBe true
            ctx.hasDpad shouldBe true
            ctx.prefersLargeTargets shouldBe true
            ctx.hasTouch shouldBe false
            ctx.canEdit shouldBe false
            ctx.supportsFullLibrary shouldBe true
        }

        test("desktopCanEditButNoDpad") {
            val ctx = DeviceContext(DeviceType.Desktop)
            ctx.canEdit shouldBe true
            ctx.hasDpad shouldBe false
            ctx.hasTouch shouldBe false
            ctx.supportsFullLibrary shouldBe true
        }

        test("watchIsWearable") {
            val ctx = DeviceContext(DeviceType.Watch)
            ctx.isWearable shouldBe true
            ctx.hasTouch shouldBe true
            ctx.canEdit shouldBe false
            ctx.supportsFullLibrary shouldBe false
        }

        test("autoHasDpadAndLargeTargets") {
            val ctx = DeviceContext(DeviceType.Auto)
            ctx.hasDpad shouldBe true
            ctx.prefersLargeTargets shouldBe true
            ctx.hasTouch shouldBe false
            ctx.canEdit shouldBe false
        }

        test("xrHasTouchAndLargeTargets") {
            val ctx = DeviceContext(DeviceType.Xr)
            ctx.hasTouch shouldBe true
            ctx.prefersLargeTargets shouldBe true
            ctx.hasDpad shouldBe false
        }

        test("tabletCapabilities") {
            val ctx = DeviceContext(DeviceType.Tablet)
            ctx.hasTouch shouldBe true
            ctx.canEdit shouldBe true
            ctx.supportsFullLibrary shouldBe true
            ctx.hasDpad shouldBe false
            ctx.isLeanback shouldBe false
        }

        test("allDeviceTypes") {
            // Ensure every DeviceType creates a valid DeviceContext
            DeviceType.entries.forEach { type ->
                val ctx = DeviceContext(type)
                ctx.type shouldBe type
            }
        }

        test("supportsDownloads true for Phone") {
            DeviceContext(DeviceType.Phone).supportsDownloads shouldBe true
        }

        test("supportsDownloads true for Tablet") {
            DeviceContext(DeviceType.Tablet).supportsDownloads shouldBe true
        }

        test("supportsDownloads true for Watch") {
            DeviceContext(DeviceType.Watch).supportsDownloads shouldBe true
        }

        test("supportsDownloads false for Desktop downloads not implemented UI hides affordance per W8 Phase A") {
            DeviceContext(DeviceType.Desktop).supportsDownloads shouldBe false
        }

        test("supportsDownloads false for Tv") {
            DeviceContext(DeviceType.Tv).supportsDownloads shouldBe false
        }

        test("supportsDownloads false for Auto") {
            DeviceContext(DeviceType.Auto).supportsDownloads shouldBe false
        }

        test("supportsDownloads false for Xr") {
            DeviceContext(DeviceType.Xr).supportsDownloads shouldBe false
        }

        test("supportsDownloads coverage exactly one outcome per DeviceType") {
            val all = DeviceType.entries.associateWith { DeviceContext(it).supportsDownloads }
            all.size shouldBe DeviceType.entries.size
        }
    })

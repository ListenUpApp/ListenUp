package com.calypsan.listenup.client.device

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DeviceInfoProviderTest :
    FunSpec({
        test("provider yields the current device info") {
            val p = DeviceInfoProvider { DeviceInfo(platform = "Android", deviceModel = "Pixel 10") }
            p.current().deviceModel shouldBe "Pixel 10"
            p.current().platform shouldBe "Android"
        }
    })

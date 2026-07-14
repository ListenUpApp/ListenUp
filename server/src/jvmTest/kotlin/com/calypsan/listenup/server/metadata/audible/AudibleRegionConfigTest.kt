package com.calypsan.listenup.server.metadata.audible

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AudibleRegionConfigTest :
    FunSpec({
        test("localeCookie maps every region to its storefront cookie") {
            val expected =
                mapOf(
                    AudibleRegion.US to "lc-main=en_US; i18n-prefs=USD",
                    AudibleRegion.UK to "lc-acbuk=en_GB; i18n-prefs=GBP",
                    AudibleRegion.DE to "lc-acbde=de_DE; i18n-prefs=EUR",
                    AudibleRegion.FR to "lc-acbfr=fr_FR; i18n-prefs=EUR",
                    AudibleRegion.AU to "lc-acbau=en_AU; i18n-prefs=AUD",
                    AudibleRegion.CA to "lc-acbca=en_CA; i18n-prefs=CAD",
                    AudibleRegion.JP to "lc-acbjp=ja_JP; i18n-prefs=JPY",
                    AudibleRegion.IT to "lc-acbit=it_IT; i18n-prefs=EUR",
                    AudibleRegion.IN to "lc-acbin=en_IN; i18n-prefs=INR",
                    AudibleRegion.ES to "lc-acbes=es_ES; i18n-prefs=EUR",
                )

            AudibleRegion.entries.forEach { region ->
                region.localeCookie() shouldBe expected.getValue(region)
            }
        }

        test("localeCookie covers every region — no AudibleRegion value is left unmapped") {
            AudibleRegion.entries.forEach { region ->
                region.localeCookie().isNotBlank() shouldBe true
            }
        }
    })

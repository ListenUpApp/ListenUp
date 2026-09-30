package com.calypsan.listenup.client.data.discovery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import platform.Foundation.NSNetServicesErrorCode
import platform.Foundation.NSNetServicesErrorDomain
import platform.Foundation.NSNumber
import platform.Foundation.numberWithInt

/**
 * A Bonjour browse refused by Local Network privacy fails with `kDNSServiceErr_PolicyDenied`
 * (TN3179). Only that code may be reported as a permission problem; any other browse failure is
 * not the user's to fix in Settings.
 */
class BonjourPolicyDenialTest :
    FunSpec({
        test("PolicyDenied as an NSNumber is a denial") {
            isBonjourPolicyDenial(mapOf(NSNetServicesErrorCode to NSNumber.numberWithInt(-65570))) shouldBe true
        }

        test("PolicyDenied as a bridged Kotlin number is a denial") {
            isBonjourPolicyDenial(mapOf(NSNetServicesErrorCode to -65570, NSNetServicesErrorDomain to 10)) shouldBe true
        }

        test("any other browse failure is not a denial") {
            isBonjourPolicyDenial(mapOf(NSNetServicesErrorCode to NSNumber.numberWithInt(-72000))) shouldBe false
        }

        test("a dictionary without an error code is not a denial") {
            isBonjourPolicyDenial(emptyMap<Any?, Any?>()) shouldBe false
        }
    })

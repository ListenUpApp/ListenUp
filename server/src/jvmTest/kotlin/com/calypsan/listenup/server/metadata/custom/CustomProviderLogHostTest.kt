package com.calypsan.listenup.server.metadata.custom

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class CustomProviderLogHostTest :
    FunSpec({
        test("a base URL carrying a token in its query and userinfo is logged as its host alone") {
            val logged = loggableHost("https://operator:hunter2@meta.example.com/api/v1?token=s3cr3t")

            logged shouldBe "meta.example.com"
            logged shouldNotContain "s3cr3t"
            logged shouldNotContain "hunter2"
        }

        test("a token in the path is not part of the host") {
            loggableHost("http://10.0.0.5:8080/s3cr3t/") shouldBe "10.0.0.5"
        }

        test("a base URL with no readable host is logged as the custom provider") {
            loggableHost("") shouldBe "custom provider"
        }
    })

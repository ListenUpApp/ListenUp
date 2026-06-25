package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class Sha256Test :
    FunSpec({

        test("hashBytesSha256 matches known vectors") {
            hashBytesSha256(ByteArray(0)) shouldBe
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            hashBytesSha256("abc".encodeToByteArray()) shouldBe
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        }
    })

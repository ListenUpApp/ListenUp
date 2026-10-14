package com.calypsan.listenup.client.core.image

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A photo's version is the lowercase SHA-256 hex of its bytes — the same stem the server names the stored file with. */
class PhotoVersionTest :
    FunSpec({
        test("photoVersionOf is the lowercase SHA-256 hex of the bytes") {
            photoVersionOf("abc".encodeToByteArray()) shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        }
    })

package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.routes.BundleEncoding.BROTLI
import com.calypsan.listenup.server.routes.BundleEncoding.GZIP
import com.calypsan.listenup.server.routes.BundleEncoding.IDENTITY
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Which precompressed variant of a web-bundle file a request gets. Pure negotiation, so it lives in
 * commonTest and runs on the linuxX64 lane too — the binary that ships.
 */
class AcceptEncodingTest :
    FunSpec({
        val both = setOf(BROTLI, GZIP)

        test("no header means the raw file") {
            negotiateEncoding(null, both) shouldBe IDENTITY
            negotiateEncoding("", both) shouldBe IDENTITY
        }

        test("brotli wins over gzip when both are accepted equally") {
            negotiateEncoding("gzip, deflate, br, zstd", both) shouldBe BROTLI
        }

        test("gzip is served when brotli is not accepted") {
            negotiateEncoding("gzip, deflate", both) shouldBe GZIP
        }

        test("x-gzip is gzip") {
            negotiateEncoding("x-gzip", both) shouldBe GZIP
        }

        test("identity alone means the raw file") {
            negotiateEncoding("identity", both) shouldBe IDENTITY
        }

        test("q=0 excludes an encoding") {
            negotiateEncoding("br;q=0, gzip", both) shouldBe GZIP
            negotiateEncoding("br;q=0, gzip;q=0", both) shouldBe IDENTITY
        }

        test("a higher q wins over the house preference") {
            negotiateEncoding("br;q=0.4, gzip;q=0.9", both) shouldBe GZIP
        }

        test("tokens and parameters are case- and space-insensitive") {
            negotiateEncoding("  BR ; Q=0 ,GZip ", both) shouldBe GZIP
        }

        test("a wildcard accepts what is not listed, and can be refused") {
            negotiateEncoding("*", both) shouldBe BROTLI
            negotiateEncoding("*;q=0, gzip", both) shouldBe GZIP
        }

        test("an accepted encoding with no variant on disk falls back to the next best") {
            negotiateEncoding("br, gzip", setOf(GZIP)) shouldBe GZIP
            negotiateEncoding("br", emptySet()) shouldBe IDENTITY
        }

        test("when nothing acceptable exists the raw file is still served, never a 406") {
            negotiateEncoding("identity;q=0, br", setOf(GZIP)) shouldBe IDENTITY
        }

        test("an unparseable q counts as absent, not as a refusal") {
            negotiateEncoding("br;q=banana", both) shouldBe BROTLI
        }
    })

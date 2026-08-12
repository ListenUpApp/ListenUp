package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.api.error.MetadataError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests for [SafeCoverUrl] — the SSRF guard that decides whether a caller- or provider-supplied
 * image URL is safe for the server to fetch.
 */
class SafeCoverUrlTest :
    FunSpec({

        test("accepts a public HTTPS provider host") {
            SafeCoverUrl.validate("https://api.audible.com/1.0/catalog/products/B123.jpg").shouldBeNull()
        }

        test("accepts a public HTTPS host with a path and query string") {
            SafeCoverUrl.validate("https://is1-ssl.mzstatic.com/image/thumb/cover.jpg?w=600").shouldBeNull()
        }

        test("rejects a plain http URL") {
            val error = SafeCoverUrl.validate("http://api.audible.com/cover.jpg")
            error.shouldNotBeNull()
            error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
        }

        test("rejects a loopback host by IP literal") {
            val error = SafeCoverUrl.validate("https://127.0.0.1/admin")
            error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
        }

        test("rejects the loopback hostname 'localhost'") {
            val error = SafeCoverUrl.validate("https://localhost/admin")
            error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
        }

        test("rejects a private-range 10.x host") {
            val error = SafeCoverUrl.validate("https://10.0.0.5/cover.jpg")
            error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
        }

        test("rejects a private-range 192.168.x host") {
            val error = SafeCoverUrl.validate("https://192.168.1.1/cover.jpg")
            error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
        }

        test("rejects a private-range 172.16-31.x host") {
            val error = SafeCoverUrl.validate("https://172.20.0.4/cover.jpg")
            error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
        }

        test("rejects the link-local cloud-metadata host 169.254.169.254") {
            // Representative of the class of destination a redirect hop must also be re-checked
            // against — re-running this same validator on a Location header target closes the
            // classic bypass where a public host 302s to an internal one.
            val error = SafeCoverUrl.validate("https://169.254.169.254/latest/meta-data/")
            error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
        }

        test("rejects an IPv6 loopback host") {
            val error = SafeCoverUrl.validate("https://[::1]/cover.jpg")
            error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
        }

        test("rejects an IPv6 unique-local host") {
            val error = SafeCoverUrl.validate("https://[fc00::1]/cover.jpg")
            error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
        }

        test("rejects an IPv6 link-local host") {
            val error = SafeCoverUrl.validate("https://[fe80::1]/cover.jpg")
            error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
        }

        test("accepts a global-unicast IPv6 host") {
            SafeCoverUrl.validate("https://[2600::1]/cover.jpg").shouldBeNull()
        }

        test("rejects an unparseable URL") {
            val error = SafeCoverUrl.validate("not a url at all")
            error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
        }
    })

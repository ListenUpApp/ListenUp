package com.calypsan.listenup.server.metadata.custom

import com.calypsan.listenup.api.metadata.MetadataDomain
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class CustomProviderConfigTest :
    FunSpec({

        test("a blank or null value yields no providers") {
            CustomProviderSpec.parse(null).shouldBeEmpty()
            CustomProviderSpec.parse("   ").shouldBeEmpty()
        }

        test("a bare name=baseUrl entry advertises every supported capability") {
            val spec = CustomProviderSpec.parse("catalog=https://meta.example.com").single()
            spec.name shouldBe "catalog"
            spec.id shouldBe MetadataProviderId.custom("catalog")
            spec.id.value shouldBe "custom:catalog"
            spec.baseUrl shouldBe "https://meta.example.com"
            spec.capabilities shouldBe CustomProviderSpec.SUPPORTED_CAPABILITIES
        }

        test("a |caps suffix narrows the advertised capabilities to the declared domains") {
            val spec = CustomProviderSpec.parse("mysource=https://x.y|characters,cover").single()
            spec.capabilities shouldContainExactlyInAnyOrder listOf(MetadataDomain.CHARACTERS, MetadataDomain.COVER)
        }

        test("a trailing slash on the base URL is trimmed so appended paths stay well-formed") {
            CustomProviderSpec.parse("s=https://x.y/").single().baseUrl shouldBe "https://x.y"
        }

        test("multiple semicolon-separated entries parse independently and in order") {
            val specs = CustomProviderSpec.parse("a=https://a.test|characters; b=https://b.test|genres")
            specs.map { it.name } shouldContainExactly listOf("a", "b")
            specs[0].capabilities shouldContainExactly listOf(MetadataDomain.CHARACTERS)
            specs[1].capabilities shouldContainExactly listOf(MetadataDomain.GENRES)
        }

        test("a malformed entry with no '=' is skipped, leaving the valid entries") {
            val specs = CustomProviderSpec.parse("garbage-no-equals; good=https://good.test|characters")
            specs.map { it.name } shouldContainExactly listOf("good")
        }

        test("an entry with a blank name or base URL is skipped") {
            CustomProviderSpec.parse("=https://x.y").shouldBeEmpty()
            CustomProviderSpec.parse("name=").shouldBeEmpty()
        }

        test("unknown and unsupported capability tokens are dropped, keeping the valid ones") {
            // 'contributors' is a real domain but not supported by the custom client; 'bogus' is unknown.
            val spec = CustomProviderSpec.parse("s=https://x.y|characters,contributors,bogus").single()
            spec.capabilities shouldContainExactly listOf(MetadataDomain.CHARACTERS)
        }

        test("an entry whose |caps names no supported capability is skipped entirely") {
            CustomProviderSpec.parse("s=https://x.y|contributors,chapters").shouldBeEmpty()
        }

        test("a duplicate name keeps the first entry and skips the rest") {
            val specs = CustomProviderSpec.parse("dup=https://first.test|characters; dup=https://second.test|genres")
            specs.single().baseUrl shouldBe "https://first.test"
            specs.single().capabilities shouldContainExactly listOf(MetadataDomain.CHARACTERS)
        }

        test("names are lowercased so config and route tokens resolve to the same id") {
            CustomProviderSpec.parse("MySource=https://x.y").single().id shouldBe MetadataProviderId.custom("mysource")
        }
    })

package com.calypsan.listenup.server.metadata.spi

import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataDomain
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class EnrichmentRoutesTest :
    FunSpec({
        val audible = MetadataProviderId.AUDIBLE
        val audnexus = MetadataProviderId.AUDNEXUS
        val itunes = MetadataProviderId.ITUNES

        // ---- orderFor: field override beats domain; domain default otherwise ----

        test("orderFor falls through to the domain order when the field has no override") {
            val routes =
                EnrichmentRoutes(
                    domainOrder = mapOf(MetadataDomain.CONTRIBUTORS to listOf(audnexus, audible)),
                    fieldOverrides = emptyMap(),
                )
            routes.orderFor(BookField.AUTHORS) shouldBe listOf(audnexus, audible)
        }

        test("orderFor prefers a field override over the domain order") {
            val routes =
                EnrichmentRoutes(
                    domainOrder = mapOf(MetadataDomain.CONTRIBUTORS to listOf(audnexus, audible)),
                    fieldOverrides = mapOf(BookField.NARRATORS to listOf(audible)),
                )
            // NARRATORS overridden, AUTHORS still follows the domain.
            routes.orderFor(BookField.NARRATORS) shouldBe listOf(audible)
            routes.orderFor(BookField.AUTHORS) shouldBe listOf(audnexus, audible)
        }

        // ---- code defaults ----

        test("DEFAULT covers every domain and encodes the approved code defaults") {
            val d = EnrichmentRoutes.DEFAULT
            MetadataDomain.entries.forEach { domain ->
                d.domainOrder.containsKey(domain) shouldBe true
            }
            d.orderFor(BookField.TITLE) shouldBe listOf(audible, audnexus)
            d.orderFor(BookField.AUTHORS) shouldBe listOf(audnexus, audible)
            d.orderFor(BookField.CHAPTERS) shouldBe listOf(audnexus, audible)
            d.orderFor(BookField.COVER) shouldBe listOf(audible, itunes)
            d.orderFor(BookField.GENRES) shouldBe listOf(audible, audnexus)
            d.orderFor(BookField.SERIES) shouldBe listOf(audible, audnexus)
        }

        test("CHARACTERS is the honest empty slot") {
            EnrichmentRoutes.DEFAULT.domainOrder.getValue(MetadataDomain.CHARACTERS) shouldBe emptyList()
        }

        // ---- H4: the dead 'local' pseudo-provider is gone, not silently stranding a route ----

        test("'local' is not a known provider token and no default route names it") {
            // Nothing resolves 'local', so a config token for it must be rejected outright rather
            // than parsed into a chain slot that walks to nothing.
            MetadataProviderId.fromToken("local") shouldBe null
            MetadataProviderId.known.none { it.value == "local" } shouldBe true
            EnrichmentRoutes.DEFAULT.domainOrder.values
                .none { chain -> chain.any { it.value == "local" } } shouldBe true
        }

        // ---- parse: global order + route clauses ----

        test("null env yields the code defaults") {
            EnrichmentRoutes.parse(order = null, routes = null) shouldBe EnrichmentRoutes.DEFAULT
        }

        test("LISTENUP_ENRICHMENT_ORDER sets a global baseline for every domain") {
            val parsed = EnrichmentRoutes.parse(order = "audible,audnexus,itunes", routes = null)
            MetadataDomain.entries.forEach { domain ->
                if (domain == MetadataDomain.CHARACTERS) return@forEach
                parsed.domainOrder.getValue(domain) shouldBe listOf(audible, audnexus, itunes)
            }
        }

        test("route clauses override domains and fields; a field clause beats its domain clause") {
            val parsed =
                EnrichmentRoutes.parse(
                    order = null,
                    routes =
                        "contributors=audnexus,audible; chapters=audnexus,audible; " +
                            "cover=itunes,audible; description=audible; narrators=itunes",
                )
            // Domain clause.
            parsed.orderFor(BookField.AUTHORS) shouldBe listOf(audnexus, audible)
            parsed.orderFor(BookField.CHAPTERS) shouldBe listOf(audnexus, audible)
            parsed.orderFor(BookField.COVER) shouldBe listOf(itunes, audible)
            // Field clauses (description is BOOK_CORE, narrators is CONTRIBUTORS).
            parsed.orderFor(BookField.DESCRIPTION) shouldBe listOf(audible)
            parsed.orderFor(BookField.NARRATORS) shouldBe listOf(itunes)
            // A sibling field under an overridden domain still follows the domain clause.
            parsed.orderFor(BookField.TITLE) shouldBe EnrichmentRoutes.DEFAULT.orderFor(BookField.TITLE)
        }

        test("collision tokens (cover/chapters/series) resolve as DOMAIN clauses, not fields") {
            val parsed = EnrichmentRoutes.parse(order = null, routes = "series=itunes")
            // SERIES token is both a domain and a field name; domain wins → domainOrder changes,
            // not a field override, so the whole SERIES domain is affected.
            parsed.domainOrder.getValue(MetadataDomain.SERIES) shouldBe listOf(itunes)
            parsed.fieldOverrides.containsKey(BookField.SERIES) shouldBe false
        }

        // ---- never-strand: malformed input falls back, never throws ----

        test("malformed clauses are skipped, valid siblings survive, nothing throws") {
            val parsed =
                EnrichmentRoutes.parse(
                    order = "audible,bogusprovider",
                    routes = "garbage-no-equals; =audible; description=; cover=itunes,unknownprov; foo=audible",
                )
            // Unknown provider dropped from global order, valid one kept → global baseline of just audible.
            parsed.domainOrder.getValue(MetadataDomain.BOOK_CORE) shouldBe listOf(audible)
            // cover clause keeps the valid provider, drops the unknown one.
            parsed.orderFor(BookField.COVER) shouldBe listOf(itunes)
            // 'foo' is neither a domain nor a field token → skipped, no field override leaked.
            parsed.fieldOverrides.keys.none { it.token == "foo" } shouldBe true
        }

        test("blank env strings behave like null") {
            EnrichmentRoutes.parse(order = "   ", routes = "  ;  ; ") shouldBe EnrichmentRoutes.DEFAULT
        }

        test("a custom:<name> token resolves so a route can name an operator's custom provider") {
            val parsed = EnrichmentRoutes.parse(order = null, routes = "characters=custom:mysource")
            parsed.domainOrder.getValue(MetadataDomain.CHARACTERS) shouldBe listOf(MetadataProviderId.custom("mysource"))
        }

        test("property: arbitrary garbage never throws and always leaves every domain routable") {
            checkAll(Arb.string(), Arb.string()) { order, routes ->
                val parsed = EnrichmentRoutes.parse(order = order, routes = routes)
                // The never-strand invariant: orderFor resolves for every field without throwing.
                BookField.entries.forEach { field -> parsed.orderFor(field) }
                MetadataDomain.entries.forEach { domain ->
                    parsed.domainOrder.containsKey(domain) shouldBe true
                }
            }
        }
    })

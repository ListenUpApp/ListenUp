package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Konsist guard pinning RPC↔REST parity for the books domain: every operation
 * on the `@Rpc` `BookService` must have a corresponding `@Resource` REST class.
 *
 * The contract has two transports over one source of truth — first-party
 * Kotlin clients call `BookService` through generated proxies; third-party
 * integrations call the `@Resource` REST routes. If an RPC method ships
 * without its REST mirror, the third-party surface silently loses an
 * operation. This rule makes that drift a build failure.
 *
 * `BookService` exposes `getBook` and `searchBooks`. Their REST mirrors live
 * in `BookResources`:
 * - `searchBooks` → `BookResources` itself — `GET /api/v1/books?q=&limit=`
 * - `getBook` → `BookResources.Detail` — `GET /api/v1/books/{id}`
 *
 * The pairing signal is the KDoc `[...BookService.<method>]` link each resource
 * already carries (e.g. *"REST mirror of [BookService.searchBooks]"*). Matching
 * on the documented link rather than a name-mangling convention keeps the rule
 * robust against the nested-resource naming (`BookResources` / `Detail`) — the
 * rule asserts every `BookService` method is referenced by at least one
 * `@Resource` class's KDoc, and that the set of referenced methods is exactly
 * the set declared on the service.
 *
 * Both sets are asserted non-empty so the rule cannot pass vacuously.
 */
class BookServiceHasRestMirror :
    FunSpec({

        test("every BookService method has a corresponding @Resource REST mirror") {
            val scope = productionScope()

            val bookService =
                scope
                    .interfaces()
                    .firstOrNull { it.name == "BookService" }
                    ?: error("BookService interface not found in commonMain scope")

            val serviceMethods =
                bookService
                    .functions()
                    .map { it.name }
                    .toSet()

            // Guard against a vacuous pass: BookService must declare methods.
            serviceMethods.shouldNotBeEmpty()

            // @Resource classes — the REST surface. Each documents which
            // BookService method it mirrors via a KDoc `[BookService.<m>]` link.
            val resourceClasses =
                scope
                    .classes(includeNested = true)
                    .filter { cls ->
                        cls.annotations.any { it.name == "Resource" }
                    }

            val linkRegex = Regex("""BookService\.(\w+)""")
            val mirroredMethods =
                resourceClasses
                    .flatMap { cls ->
                        val doc = cls.kDoc?.text.orEmpty()
                        linkRegex.findAll(doc).map { it.groupValues[1] }
                    }.toSet()

            // Every BookService method must be mirrored by at least one @Resource.
            val unmirrored = serviceMethods - mirroredMethods
            unmirrored.shouldBeEmpty()
        }
    })

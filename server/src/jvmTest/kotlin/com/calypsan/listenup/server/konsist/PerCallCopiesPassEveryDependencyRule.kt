package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.core.spec.style.FunSpec

/**
 * Every per-call copy of a service passes every constructor parameter it was given.
 *
 * Around twenty `*ServiceImpl` classes expose a hand-written `copyWith(principal)` /
 * `withRemoteHost(host)` / `withUserAgent(ua)` that rebuilds the class by listing all of its
 * collaborators, and **every RPC request runs through one** (`registerScoped` calls `copyWith` per
 * call). A parameter added to the constructor but forgotten in one of those lists is silently
 * defaulted away, for that request path only.
 *
 * That is not hypothetical. `AuthServiceImpl` gained a `socketTicketStore` and all three of its
 * copies dropped it; because the public RPC mount reaches the service through `withRemoteHost`, the
 * browser's socket tickets could not be minted, and **the only symptom was a 401 with nothing in
 * the logs** — a service with no store cannot mint, so no ticket reaches the URL, so the bearer
 * provider never gets far enough to log a rejection. Every other copy in the module was correct at
 * the time, so this guards a trap rather than a standing violation.
 *
 * Counted rather than name-matched on purpose: 13 of these copies pass their arguments
 * **positionally**, which is not safer than naming them — a new parameter carrying a default is
 * dropped just the same, it merely cannot be spotted by name. Arity is the property that holds for
 * both styles.
 */
class PerCallCopiesPassEveryDependencyRule :
    FunSpec({

        /**
         * The number of top-level arguments in the first `ClassName(...)` call inside [body], or
         * null when it constructs nothing — a same-type-returning function that is not a copy.
         *
         * Scans balanced parentheses rather than regex-matching the argument list: nested calls,
         * generics and trailing lambdas all contain commas that must not be counted.
         */
        fun constructorArgCount(
            body: String,
            className: String,
        ): Int? {
            val marker = "$className("
            val open = body.indexOf(marker).takeIf { it >= 0 } ?: return null
            var depth = 1
            var index = open + marker.length
            val args = StringBuilder()
            while (index < body.length && depth > 0) {
                when (val ch = body[index]) {
                    '(', '<', '[' -> {
                        depth++
                        args.append(ch)
                    }

                    ')', '>', ']' -> {
                        depth--
                        if (depth > 0) args.append(ch)
                    }

                    else -> {
                        args.append(ch)
                    }
                }
                index++
            }
            var nesting = 0
            var count = if (args.isBlank()) 0 else 1
            for (ch in args) {
                when (ch) {
                    '(', '<', '[' -> nesting++
                    ')', '>', ']' -> nesting--
                    ',' -> if (nesting == 0) count++
                }
            }
            return count
        }

        test("every per-call copy passes every constructor parameter") {
            val classes =
                Konsist
                    .scopeFromProduction()
                    .classes()
                    .filter { it.path.contains("/server/src/commonMain/") }
                    .filter { it.primaryConstructor != null }

            val copies =
                classes.flatMap { cls ->
                    cls
                        .functions()
                        .filter { fn ->
                            fn.returnType?.name == cls.name &&
                                (fn.name == "copyWith" || fn.name.matches(Regex("with[A-Z]\\w*")))
                        }.mapNotNull { fn ->
                            constructorArgCount(stripComments(fn.text), cls.name)?.let { passed ->
                                Triple(cls, fn, passed)
                            }
                        }
                }

            // Guard against a vacuous pass: the ~20 real service copies must be observed. A rule
            // that silently matches nothing has stopped guarding.
            copies.size shouldBeGreaterThanOrEqual 15

            val offenders =
                copies.mapNotNull { (cls, fn, passed) ->
                    val declared = cls.primaryConstructor?.parameters?.size ?: 0
                    if (passed < declared) {
                        "${cls.name}.${fn.name}() passes $passed of $declared constructor parameters @ ${fn.path}"
                    } else {
                        null
                    }
                }

            offenders.shouldBeEmpty()
        }
    })

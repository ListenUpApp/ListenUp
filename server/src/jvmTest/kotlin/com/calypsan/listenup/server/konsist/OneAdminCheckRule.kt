package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * The server spells "is this caller an admin?" once, in `auth/Roles.kt`. Main once had more than a dozen
 * private copies; a copy that drifts (say, forgets ROOT) is a privilege bug nobody would see.
 */
class OneAdminCheckRule :
    FunSpec({
        test("only auth/Roles.kt compares a role against ROOT and ADMIN") {
            val files = Konsist.scopeFromProduction("server").files
            files.size shouldBeGreaterThanOrEqual 300
            val spelled = Regex("""UserRole(Column)?\.ROOT\s*\|\|""")
            val offenders =
                files
                    .filterNot { it.path.endsWith("/auth/Roles.kt") }
                    .filter { spelled.containsMatchIn(stripComments(it.text)) }
                    .map { it.path }
            withClue("Private admin checks:\n" + offenders.joinToString("\n")) { offenders.shouldBeEmpty() }
        }
    })

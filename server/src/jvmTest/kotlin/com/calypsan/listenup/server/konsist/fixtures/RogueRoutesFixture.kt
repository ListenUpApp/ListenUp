package com.calypsan.listenup.server.konsist.fixtures

import com.calypsan.listenup.server.auth.isAdmin
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * Deliberately violates [com.calypsan.listenup.server.konsist.MutatingRoutesAreGatedRule], so the
 * self-test can prove the rule fires. Lives under `jvmTest`, so production scope never sees it.
 *
 * Exactly four offenders: `/rogue/ungated` (no gate), `/rogue/comment-gate` (its only gate is in a
 * comment), `/rogue/blank-reason` (the open-to-all marker has no reason) and `/rogue/bare-is-admin`
 * (it reads `isAdmin()` but denies nothing). The other four pass: a gate helper, an admin check that
 * denies, a scoped service and a reasoned marker.
 */
internal fun Route.rogueRoutes() {
    post("/rogue/ungated") {
        call.respond(HttpStatusCode.OK)
    }

    delete("/rogue/comment-gate") {
        // if (!call.requireFixtureAdmin()) return@delete
        call.respond(HttpStatusCode.NoContent)
    }

    put("/rogue/blank-reason") {
        // open-to-all:
        call.respond(HttpStatusCode.OK)
    }

    post("/rogue/bare-is-admin") {
        val wasAdmin = call.userPrincipalOrNull()?.role?.isAdmin() == true
        call.respond(if (wasAdmin) HttpStatusCode.OK else HttpStatusCode.Accepted)
    }

    post("/rogue/helper-gated") {
        if (!call.requireFixtureAdmin()) return@post
        call.respond(HttpStatusCode.OK)
    }

    delete("/rogue/admin-denies") {
        val principal = call.userPrincipalOrNull() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
        if (!principal.role.isAdmin()) return@delete call.respond(HttpStatusCode.Forbidden)
        call.respond(HttpStatusCode.NoContent)
    }

    put("/rogue/scoped") {
        call.respond(call.scoped("service"))
    }

    post("/rogue/own-thing") {
        // open-to-all: a caller changes only their own thing, keyed by their principal
        call.respond(HttpStatusCode.OK)
    }
}

private fun ApplicationCall.requireFixtureAdmin(): Boolean = userPrincipalOrNull()?.role?.isAdmin() == true

private fun ApplicationCall.scoped(service: String): String = service

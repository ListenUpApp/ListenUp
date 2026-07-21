package com.calypsan.listenup.server.foundation

import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Mounts the REST / authed smoke routes proving those transports serve on the native skeleton:
 *  - `GET /healthz` → 200 `{"status":"ok"}` (REST + ContentNegotiation).
 *  - `GET /healthz/whoami` behind [JWT_PROVIDER] → echoes the caller's user id (JwtAuth; 401 without
 *    a valid token, which the auth provider enforces before the handler runs).
 *
 * The RPC transport (the riskiest native piece) is installed by [installFoundation]; service
 * registration is the caller's job (see that function's KDoc on why guarded registration can't live
 * in commonMain yet).
 */
internal fun Application.mountSmokeRoutes() {
    routing {
        get("/healthz") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
        authenticate(JWT_PROVIDER) {
            get("/healthz/whoami") {
                call.respondText(
                    call
                        .userPrincipalOrNull()
                        ?.userId
                        ?.value
                        .orEmpty(),
                )
            }
        }
    }
}

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.server.db.resolveListenupHome
import com.calypsan.listenup.server.io.readEnv
import com.calypsan.listenup.server.io.userHomeDir
import io.ktor.server.config.ApplicationConfig

/**
 * Resolves both server secrets from [config] at startup. Reads the raw
 * `LISTENUP_JWT_SECRET` / `LISTENUP_REFRESH_PEPPER` env vars (the explicit channel)
 * and `LISTENUP_HOME` directly, then delegates to [resolveSecret]. Unconfigured
 * secrets are generated/persisted under `$LISTENUP_HOME/secrets.properties`.
 */
fun resolveServerSecrets(config: ApplicationConfig): ServerSecrets {
    // Honour `listenup.home` (config) so secrets.properties lands under the SAME home as the DB and
    // covers/spool — all server data stays under one directory.
    val home =
        resolveListenupHome(
            configuredHome = config.propertyOrNull("listenup.home")?.getString(),
            envHome = readEnv("LISTENUP_HOME"),
            userHome = userHomeDir(),
        )
    val store = FileSecretStore(home)
    return ServerSecrets(
        jwtSecret =
            resolveSecret(
                rawEnv = readEnv("LISTENUP_JWT_SECRET"),
                configValue = config.property("jwt.secret").getString(),
                committedDefault = INSECURE_DEFAULT_JWT_SECRET,
                store = store,
                storeKey = "jwt.secret",
                envVarName = "LISTENUP_JWT_SECRET",
            ),
        refreshPepper =
            resolveSecret(
                rawEnv = readEnv("LISTENUP_REFRESH_PEPPER"),
                configValue = config.property("auth.refreshPepper").getString(),
                committedDefault = INSECURE_DEFAULT_REFRESH_PEPPER,
                store = store,
                storeKey = "auth.refreshPepper",
                envVarName = "LISTENUP_REFRESH_PEPPER",
            ),
    )
}

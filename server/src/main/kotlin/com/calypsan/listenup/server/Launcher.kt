package com.calypsan.listenup.server

import io.ktor.server.cio.EngineMain

/**
 * Process entrypoint.
 *
 * Resolves the logback configuration file from `LISTENUP_LOG_FORMAT` and sets the
 * `logback.configurationFile` system property *before* any logger is created — this
 * file deliberately declares no logger, so loading it triggers no logback
 * initialization. The selection must happen here rather than in [module]'s file,
 * which holds a top-level logger that would initialize logback at class load.
 *
 * This replaces the Janino `<if>` that previously lived in `logback.xml`. Janino
 * compiles the conditional expression to bytecode at runtime, which a GraalVM
 * native image cannot do; two static config files selected at startup are both
 * native-image-safe and simpler.
 */
fun main(args: Array<String>) {
    if (System.getProperty("logback.configurationFile") == null) {
        System.setProperty(
            "logback.configurationFile",
            resolveLogbackConfigFile(System.getenv("LISTENUP_LOG_FORMAT")),
        )
    }
    EngineMain.main(args)
}

/** Maps the `LISTENUP_LOG_FORMAT` value to its logback config resource name. */
fun resolveLogbackConfigFile(logFormat: String?): String =
    if (logFormat.equals("json", ignoreCase = true)) "logback-json.xml" else "logback.xml"

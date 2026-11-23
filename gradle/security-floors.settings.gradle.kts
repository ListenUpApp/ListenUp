// =============================================================================
// SECURITY FLOORS - minimum versions for vulnerable transitive JVM dependencies
// =============================================================================
// Applied from both settings files (this build and tools/build-logic), so one list covers every
// project in the tree.
//
// A resolution rule rather than a dependency constraint because the old copies sit mostly in
// classpaths no project declares into: the plugin classpath (AGP), Android lint's tool classpath,
// the KSP processor classpaths (Room's compiler), and the Swift Export worker. A constraint only
// reaches a configuration it is declared on; this rule reaches every configuration that resolves.
// It only ever raises a version, and keeps guava's -android / -jre flavour.
//
// Remove an entry once nothing in the tree resolves below its floor without it.

// Everything lives inside the action: Gradle isolates it for the configuration cache, so it may not
// reference this script object.
gradle.lifecycle.beforeProject {
    // "group:name" to (floor version, reason).
    val securityFloors =
        mapOf(
            // Pulled by AGP 9.5 and its lint (33.4.0), Room's KSP compiler (33.2.1), media3-common, which
            // ships it in the Android app (33.3.1-android), and ktor-server-auth-jwt's jwks-rsa in the server
            // tests (33.6.0). No stable AGP, Room or media3 release moves past 33.7.1 yet.
            "com.google.guava:guava" to
                (
                    "33.7.2-jre" to
                        "GHSA-xxph-c9ww-hj94: unbounded allocation deserializing CompactHashMap, fixed in guava 33.7.2"
                ),
            // Pulled at 1.41.0 by Kotlin's swift-export-embeddable, which runs only in the Swift Export build
            // worker. No stable Kotlin release moves past it yet.
            "io.opentelemetry:opentelemetry-api" to
                ("1.67.0" to "GHSA-ccrj-gw8r-854m: quadratic tracestate parsing, fixed in opentelemetry-api 1.67.0"),
        )

    // The numeric release of a version: "33.3.1-android" -> [33, 3, 1].
    fun releaseNumbers(version: String): List<Int> =
        version.substringBefore('-').split('.').mapNotNull(String::toIntOrNull)

    fun isBelow(
        version: String,
        floor: String,
    ): Boolean {
        val actual = releaseNumbers(version)
        val minimum = releaseNumbers(floor)
        val comparison =
            actual.zip(minimum).map { (a, m) -> a.compareTo(m) }.firstOrNull { it != 0 } ?: (actual.size - minimum.size)
        return actual.isNotEmpty() && comparison < 0
    }

    fun Configuration.applySecurityFloors() {
        resolutionStrategy.eachDependency {
            val (floor, reason) = securityFloors["${requested.group}:${requested.name}"] ?: return@eachDependency
            val requestedVersion = requested.version
            if (requestedVersion != null && isBelow(requestedVersion, floor)) {
                // Keep the requested flavour (guava's -android) where there is one.
                val flavour = requestedVersion.substringAfter('-', missingDelimiterValue = "")
                useVersion(if (flavour.isEmpty()) floor else "${floor.substringBefore('-')}-$flavour")
                because(reason)
            }
        }
    }

    buildscript.configurations.configureEach { applySecurityFloors() }
    configurations.configureEach { applySecurityFloors() }
}

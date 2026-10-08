import dev.detekt.gradle.Detekt

/*
 * Type-resolved detekt for this module. The root `detekt` task analyses every source directory, but
 * without a compilation classpath, so every rule that needs types — UnsafeCallOnNullableType,
 * UnusedImport, IgnoredReturnValue, InjectDispatcher and the rest — silently never fired there
 * (reproduced 2026-10-08: planted violations stayed green while a naming rule in the same file failed).
 *
 * The detekt plugin generates one typed task per JVM or Android compilation (`detektMainJvm`,
 * `detektTestJvm`, `detektMainAndroid`, …); commonMain is analysed with full types through them. This
 * module's `detekt` task now runs exactly those, so `./gradlew detekt` at the root runs the untyped
 * pass over every source directory AND the typed pass over every module. Source sets with no JVM or
 * Android compilation (Apple, Linux, JS) keep the untyped pass only — detekt has no typed task for them.
 */
plugins {
    id("dev.detekt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("tools/detekt/detekt.yml"))
    baseline = rootProject.file("tools/detekt/baseline.xml")
    parallel = true
}

dependencies {
    // The project's own rules, the same set the root pass runs.
    "detektPlugins"("com.calypsan.listenup.build-logic:detekt-rules:0.0.1")
}

// Generated sources (KSP, Room, SQLDelight, Compose resources) are not ours to fix. The spec captures a
// plain File, never the script, so the configuration cache can store it.
tasks.withType<Detekt>().configureEach {
    val generatedSources: File =
        project.layout.buildDirectory
            .get()
            .asFile
    exclude { it.file.startsWith(generatedSources) }
}

/**
 * The type-resolved tasks: one per JVM or Android compilation, whatever the compilation is called
 * (`detektMainJvm`, `detektTestJvm`, `detektHostTestAndroid`, `detektDevDesktop`, …). Matching
 * `detektMain*`/`detektTest*` by name once left the Android host-test compilations unanalysed.
 */
fun isTypeResolved(name: String): Boolean =
    name.startsWith("detekt") &&
        name != "detekt" &&
        name != "detektGenerateConfig" &&
        !name.startsWith("detektBaseline") &&
        !name.endsWith("SourceSet")

tasks.named("detekt") {
    setDependsOn(tasks.withType<Detekt>().matching { isTypeResolved(it.name) })
}

// Declare the Kotlin JVM plugin once for the whole build-logic build so its subprojects apply it
// without their own version. :convention pulls Kotlin transitively via `kotlin-dsl` (Gradle's
// embedded Kotlin), and :detekt-rules applies `org.jetbrains.kotlin.jvm` with no version — keeping
// the Kotlin Gradle plugin from being loaded with an explicit version in more than one subproject
// (which Gradle warns about and which can break the build).
plugins {
    alias(libs.plugins.kotlinJvm) apply false
}

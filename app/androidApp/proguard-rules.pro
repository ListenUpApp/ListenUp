# ============================================================================
# ProGuard/R8 rules for ListenUp Android client
#
# Keep this file minimal. Every dependency the app relies on (Compose, Media3,
# Room, kotlinx.serialization, Koin, Ktor) ships its own consumer keep rules
# inside its artifact, so a blanket "-keep <pkg>.** { *; }" here only fences code
# off from R8 full mode with no functional benefit. Add a rule only for a
# concrete, observed reflective failure, and scope it as narrowly as possible.
#
# The "-dontwarn" rules below suppress missing-class warnings for optional
# transitive APIs. They do not affect shrinking, so they are kept as a low-cost
# safety net rather than removed.
# ============================================================================

# --- kotlinx.serialization ---
# Keep annotation + inner-class metadata for (de)serialization reflection. The
# serialization runtime bundles the keep rules for the generated $$serializer
# classes, companions, and serializer() on every @Serializable type, so no
# manual class keeps are required here.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# --- Ktor (OkHttp engine) ---
# The authenticated client is built with a no-arg HttpClient { }, which resolves
# its engine via ServiceLoader at runtime. Keep the OkHttp engine container so R8
# full mode cannot strip the discovered implementation. The rest of Ktor is
# preserved by normal reachability plus Ktor's own consumer rules.
-keep class io.ktor.client.engine.okhttp.** { *; }

# --- Warning suppression (does not affect shrinking) ---
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn coil3.**
-dontwarn androidx.**
-dontwarn org.slf4j.**
-dontwarn kotlin.**
-dontwarn kotlinx.**

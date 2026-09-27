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
#
# Signature is required by kotlinx.rpc (below), which reads generic return types
# — AppResult<T>, Flow<T> — off the service interface to pick a deserializer.
# Without it every RPC method erases to its raw type and decoding fails.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt

# --- kotlinx.rpc ---
# UNLIKE every other dependency here, kotlinx.rpc ships NO consumer keep rules
# (verified against the 0.11.0 artifacts). Its client resolves each @Rpc service
# by interface name to build the proxy, so R8 renaming them breaks EVERY RPC call
# at proxy-construction time — before a socket is opened. The whole app surface is
# RPC, so the app cannot reach any server at all; it surfaces as a generic
# "couldn't verify the server" with zero network activity and, in a release build,
# zero logs. Shipped in 0.8.0 (versionCode 2756) and reproduced on-device.
#
# How a proxy is built (kotlinx.rpc 0.11, JVM): `serviceDescriptorOf<T>()` reads the
# @WithServiceDescriptor annotation off the @Rpc interface with kotlin-reflect and takes the
# `objectInstance` of the class it names — the plugin-generated `T$$rpcServiceStub$Companion`, which
# holds the service's FQ name, its callables and `createInstance`, and builds the
# `T$$rpcServiceStub` proxy. Every step is reflective, so the interface, both stub classes and the
# runtime that performs the lookup are kept whole.
#
# Keeping only the @Rpc interfaces was tried first (0.8.0) and was NOT sufficient: R8 still
# stripped the runtime that reads them, so kotlinx.rpc stays kept whole. The rest of :contract —
# the DTOs, the AppError hierarchy, the sync payloads — is NOT reached reflectively by name:
# kotlinx.serialization's own consumer rules keep every @Serializable class's companion and
# serializer(), which is all kotlinx.rpc resolves from the generic signatures. It used to be kept
# whole too (`com.calypsan.listenup.api.**`), which fenced ~1,650 classes off from R8; that rule
# was narrowed to the stubs, and the minified app verified end to end against a live server.
-keep @kotlinx.rpc.annotations.Rpc interface * { *; }
-keep class **$$rpcServiceStub { *; }
-keep class **$$rpcServiceStub$Companion { *; }
-keep class kotlinx.rpc.** { *; }

# --- SLF4J provider ---
# ListenUp.onCreate names this provider to SLF4J by class name (the `slf4j.provider` property), and
# SLF4J instantiates it reflectively through its no-arg constructor. Nothing else references that
# constructor, so R8 removed it and every release build logged "Failed to instantiate the specified
# SLF4JServiceProvider" at startup, silently losing the on-device log tee.
-keep class com.calypsan.listenup.client.logging.ListenUpAndroidLogProvider { <init>(); }

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

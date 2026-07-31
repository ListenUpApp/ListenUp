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
# These keeps are deliberately broader than this file's usual "narrowest possible"
# policy, and the breadth is evidence-driven rather than defensive. Keeping only the
# @Rpc interfaces was tried first and was NOT sufficient: the app still failed with no
# socket opened. A DEX diff against a working debug build showed R8 had also stripped
# the runtime that READS those interfaces — serviceDescriptorOf, rpcChannel,
# RpcProxyCache, rpcResult were all present in debug and absent from release, leaving
# only KrpcTransport. Keeping an interface is useless if the reflective machinery that
# builds a proxy from it is gone, so the runtime and the generated per-service stubs
# (which live alongside the interfaces in :contract) are kept too.
-keep @kotlinx.rpc.annotations.Rpc interface * { *; }
-keep class kotlinx.rpc.** { *; }
-keep class com.calypsan.listenup.api.** { *; }

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

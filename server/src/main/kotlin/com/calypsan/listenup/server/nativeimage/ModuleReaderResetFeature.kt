package com.calypsan.listenup.server.nativeimage

import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.FieldValueTransformer
import java.util.concurrent.ConcurrentHashMap

/**
 * GraalVM native-image [Feature] that resets `jdk.internal.loader.BuiltinClassLoader.moduleToReader`
 * to an empty map in the image heap (#647).
 *
 * The blocker: GraalVM force-initializes `java.time.zone.ZoneRulesProvider` at build time (it's too
 * late to move to run-time init), and its `<clinit>` runs a `ServiceLoader` scan that opens JDK
 * module jars and caches the resulting `ModuleReferences$JarModuleReader`s in `moduleToReader`. Those
 * readers hold open `JarFile`/`ZipFile` handles, which are then reachable from the `AppClassLoader`
 * and rejected at image-heap write ("Detected a ZipFile object in the image heap").
 *
 * The handles are never needed at native runtime — application classes are AOT-compiled and resources
 * are served from the image, not by reading jars off disk — so emptying the cache is safe. We return
 * a fresh empty [ConcurrentHashMap] (the field's normal type) rather than null so any lazy runtime
 * `moduleReaderFor` call still has a usable map.
 */
class ModuleReaderResetFeature : Feature {
    override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
        val field =
            Class.forName("jdk.internal.loader.BuiltinClassLoader")
                .getDeclaredField("moduleToReader")
        access.registerFieldValueTransformer(
            field,
            FieldValueTransformer { _, _ -> ConcurrentHashMap<Any, Any>() },
        )
    }
}

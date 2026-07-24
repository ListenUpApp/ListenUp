import com.calypsan.listenup.gradle.LISTENUP_FREE_COMPILER_ARGS
import com.calypsan.listenup.gradle.LISTENUP_MAIN_ONLY_COMPILER_ARGS
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    // Pin compilation to JDK 21 so a newer local/daemon JDK can't shift validation.
    jvmToolchain(21)

    android {
        compileSdk =
            libs
                .findVersion("android-compileSdk")
                .get()
                .requiredVersion
                .toInt()
        minSdk =
            libs
                .findVersion("android-minSdk")
                .get()
                .requiredVersion
                .toInt()

        // Android stays on JVM_17 bytecode.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(LISTENUP_FREE_COMPILER_ARGS)
        }

        // Common lint config. Per-module specifics (checkDependencies / disable) stay in the module.
        lint {
            warningsAsErrors = false
            abortOnError = true
            htmlReport = true
            xmlReport = true
        }
    }

    // Mirror the previous `targets.all { compilations.all { ... } }` pattern (now lazy):
    // apply the compiler-args triple to every compilation, and set JVM_21 on the JVM
    // target(s) only (android is handled above; native/metadata ignore jvmTarget).
    targets.configureEach {
        val isJvm = platformType == KotlinPlatformType.jvm
        compilations.configureEach {
            // Test compile tasks (compileTestKotlin*, compile*UnitTestKotlin*, …) contain "Test";
            // anything else is a production (main) compilation that must enforce AppResult must-use.
            val isMainCompilation = !compileKotlinTaskName.contains("Test", ignoreCase = true)
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.addAll(LISTENUP_FREE_COMPILER_ARGS)
                    if (isMainCompilation) {
                        freeCompilerArgs.addAll(LISTENUP_MAIN_ONLY_COMPILER_ARGS)
                    }
                    if (isJvm && this is KotlinJvmCompilerOptions) {
                        jvmTarget.set(JvmTarget.JVM_21)
                    }
                }
            }
        }
    }
}

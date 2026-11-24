import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.TestExtension
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.VersionCatalogsExtension

/*
 * Shared SDK versions and Java 17 bytecode compatibility for Android app and instrumentation-test
 * modules (:app:androidApp, :app:baselineprofile, :app:storeshots).
 */
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogInt(alias: String): Int =
    libs
        .findVersion(alias)
        .get()
        .requiredVersion
        .toInt()

val compileSdkVersion = catalogInt("android-compileSdk")
val minSdkVersion = catalogInt("android-minSdk")
val targetSdkVersion = catalogInt("android-targetSdk")

pluginManager.withPlugin("com.android.application") {
    extensions.configure<ApplicationExtension> {
        compileSdk = compileSdkVersion
        defaultConfig {
            minSdk = minSdkVersion
            targetSdk = targetSdkVersion
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

pluginManager.withPlugin("com.android.test") {
    extensions.configure<TestExtension> {
        compileSdk = compileSdkVersion
        defaultConfig {
            minSdk = minSdkVersion
            targetSdk = targetSdkVersion
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

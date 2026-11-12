plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.binary.compatibility.validator) apply false
}

// `-PreceptionComposeBom=<version>` runs the SDK on another Compose, like a host app that resolves a newer one: the
// library still compiles against its own BOM (and publishes it), while its unit tests and the example app resolve the
// given BOM, which pins Compose UI, Foundation and Material 3 together. `-PreceptionMaterial3=<version>` also forces
// Material 3 to a version the newest BOM does not pin yet (a pre-release that hosts already resolve through other
// libraries, as Expo's `@expo/ui` does). A call into a signature that Compose or Material removed then fails a test or
// the example with NoSuchMethodError. tool/release_check.sh runs this with the newest BOM and Material 3.
// A newer Compose may ask for a newer compileSdk and Android Gradle plugin than this build uses (Compose 1.12 asks for
// 37 and 9.1); a host on it has them, and what is checked here is the SDK's bytecode on that Compose, so the AAR
// metadata checks are off for this run only.
val hostComposeBom = providers.gradleProperty("receptionComposeBom").orNull
val hostMaterial3 = providers.gradleProperty("receptionMaterial3").orNull
if (hostComposeBom != null || hostMaterial3 != null) {
    subprojects {
        tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
        // The Android plugin aligns the unit tests' and the example's compile classpath with the forced runtime one,
        // and newer Compose and Material ship Kotlin metadata newer than this build's compiler reads. The SDK's own
        // classes still compile against its BOM with the full check; only the test and example sources skip it here.
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            if (project.name == "example" || name.contains("UnitTest")) {
                compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
            }
        }
        configurations.matching { configuration ->
            configuration.name.endsWith("RuntimeClasspath") &&
                (project.name == "example" || configuration.name.endsWith("UnitTestRuntimeClasspath"))
        }.configureEach {
            resolutionStrategy.eachDependency {
                val version = when {
                    requested.group == "androidx.compose" && requested.name == "compose-bom" -> hostComposeBom
                    requested.group == "androidx.compose.material3" -> hostMaterial3
                    else -> null
                }
                if (version != null) {
                    useVersion(version)
                    because("the Compose compatibility check runs the SDK on the host's Compose and Material 3")
                }
            }
        }
    }
}

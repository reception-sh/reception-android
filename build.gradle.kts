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
// given BOM, which pins Compose UI, Foundation and Material 3 together. A call into a signature that Compose removed
// then fails a test or the example with NoSuchMethodError. tool/release_check.sh runs this with the newest BOM.
// A newer Compose may ask for a newer compileSdk and Android Gradle plugin than this build uses (Compose 1.12 asks for
// 37 and 9.1); a host on it has them, and what is checked here is the SDK's bytecode on that Compose, so the AAR
// metadata checks are off for this run only.
providers.gradleProperty("receptionComposeBom").orNull?.let { hostBom ->
    subprojects {
        tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
        configurations.matching { configuration ->
            configuration.name.endsWith("RuntimeClasspath") &&
                (project.name == "example" || configuration.name.endsWith("UnitTestRuntimeClasspath"))
        }.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "androidx.compose" && requested.name == "compose-bom") {
                    useVersion(hostBom)
                    because("the Compose compatibility check runs the SDK on the host's Compose")
                }
            }
        }
    }
}

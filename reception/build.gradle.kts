import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.binary.compatibility.validator)
}

// The version lives in one place, `SDKVersion.current` (what `Reception.version` and the User-Agent report); the
// published coordinates read it from there.
val sdkVersion: String = Regex("""const val current: String = "([^"]+)"""")
    .find(file("src/main/kotlin/sh/reception/Version.kt").readText())
    ?.groupValues?.get(1)
    ?: error("SDKVersion.current not found in Version.kt")

android {
    namespace = "sh.reception"
    compileSdk = 36
    resourcePrefix = "reception_"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    lint {
        lintConfig = file("lint.xml")
        targetSdk = 36
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
    }

    testOptions {
        targetSdk = 36
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // ReceptionChatView exposes Modifier, so hosts compile against the same Compose artifacts.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    // unreadCount and isChatOpen are StateFlows.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.coil.compose)

    compileOnly(libs.firebase.messaging)
    compileOnly(libs.billing)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

// `reception/api/reception.api` lists every public symbol; `apiCheck` (part of `check`, and of tool/release_check.sh)
// fails on any change to it until `apiDump` records the change on purpose.
apiValidation {
    // Holders the Compose compiler generates for composable lambdas; public in bytecode, never API.
    ignoredClasses.addAll(
        listOf(
            "sh.reception.ComposableSingletons\$ReceptionChatViewKt",
            "sh.reception.ui.ComposableSingletons\$ChatHistoryKt",
            "sh.reception.ui.ComposableSingletons\$PushPromptSlotKt",
        ),
    )
}

mavenPublishing {
    coordinates("sh.reception", "reception", sdkVersion)
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    // Local builds and publishToMavenLocal never need a key; release machines provide one.
    val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent
    if (hasSigningKey) {
        signAllPublications()
    }

    pom {
        name.set("Reception")
        description.set("In-app support chat for Android apps.")
        url.set("https://reception.sh")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("reception")
                name.set("Reception")
                url.set("https://reception.sh")
            }
        }
        scm {
            url.set("https://github.com/reception-sh/reception-android")
            connection.set("scm:git:git://github.com/reception-sh/reception-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/reception-sh/reception-android.git")
        }
    }
}

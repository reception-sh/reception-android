import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// `-PreceptionExampleBare=true` builds the app without Firebase and Billing, like a host that uses neither.
val bare = providers.gradleProperty("receptionExampleBare").map { it.toBoolean() }.getOrElse(false)

// The Firebase configuration of the test app is not committed; without it the app builds without push.
if (!bare && file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "sh.reception.receptionAndroidExample"
    compileSdk = 36

    defaultConfig {
        applicationId = "sh.reception.receptionAndroidExample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // The App ID of the local test app stays out of git: .local/app-id.txt, empty when missing.
        val appId = rootProject.file(".local/app-id.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        buildConfigField("String", "RECEPTION_APP_ID", "\"$appId\"")
        buildConfigField("String", "RECEPTION_SERVICE_URL", "\"http://10.0.2.2:3123\"")
        // The local test app's identity secret, for the token button; never in a real app, which signs on its server.
        val identitySecret = rootProject.file(".local/identity-secret.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        buildConfigField("String", "RECEPTION_IDENTITY_SECRET", "\"$identitySecret\"")
        // The SDK's messaging service shows the replies; a bare build has no Firebase to start it.
        manifestPlaceholders["receptionMessaging"] = (!bare).toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Compose ships a prebuilt native library; without the NDK installed AGP cannot strip it and says so.
    packaging {
        jniLibs.keepDebugSymbols += "**/libandroidx.graphics.path.so"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":reception"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    if (!bare) {
        implementation(libs.firebase.messaging)
        implementation(libs.billing)
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.desktop2fa.desktop.watch"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.desktop2fa.desktop.watch"
        minSdk = 30
        targetSdk = 37
        versionCode = 4
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.security.crypto)
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.material3)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.play.services.wearable)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.wear.tooling.preview)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    // Older transitive Espresso reflects InputManager.getInstance, removed on API 37.
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}

// The optional bridge is external to the JVM classpath. Changing its inputs or
// enabling it must invalidate a previous JVM-only (skipped bridge) test result.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.property("pinnedDesktopInterop", providers.environmentVariable("D2FA_INTEROP").orElse("0"))
    inputs.property("pinnedDesktopSource", providers.environmentVariable("D2FA_DESKTOP_SOURCE").orElse(""))
    inputs.property("pinnedDesktopPython", providers.environmentVariable("D2FA_INTEROP_PYTHON").orElse(""))
    inputs.files(rootProject.fileTree("tools/interop") { include("*.py", "*.json", "requirements.txt") })
}

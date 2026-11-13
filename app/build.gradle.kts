plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Explicit developer-only packaging; the ordinary app remains offline and needs no NDK/source checkout.
val transferNativeEnabled = providers.gradleProperty("d2faTransferNative").map {
    it.toBooleanStrictOrNull() ?: error("d2faTransferNative must be true or false")
}.getOrElse(false)
val transferNativeSource = if (transferNativeEnabled) providers.gradleProperty("d2faBoringSSLSource").orNull
    ?: error("Enabled transfer native build requires the exact pinned d2faBoringSSLSource checkout") else null
val transferNotices = if (transferNativeEnabled) tasks.register<Copy>("copyTransferNativeNotices") {
    into(layout.buildDirectory.dir("generated/transfer-native-notices/legal"))
    from(rootProject.file("LICENSE")) { rename { "PROJECT-LICENSE" } }
    from(file("$transferNativeSource/LICENSE")) { rename { "BORINGSSL-LICENSE" } }
} else null

android {
    namespace = "org.desktop2fa.desktop.watch"
    if (transferNativeEnabled) ndkVersion = "28.2.13676358"
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
        buildConfigField("boolean", "TRANSFER_NATIVE_ENABLED", transferNativeEnabled.toString())
        if (transferNativeEnabled) {
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
            externalNativeBuild.cmake {
                arguments += listOf("-DD2FA_BORINGSSL_SOURCE=$transferNativeSource", "-DD2FA_JNI=ON",
                    "-DD2FA_TEST_API=OFF", "-DBUILD_TESTING=OFF", "-DANDROID_STL=c++_static",
                    "-DCMAKE_JOB_POOLS=transfer_compile=2", "-DCMAKE_JOB_POOL_COMPILE=transfer_compile")
                targets += "d2fa_transfer"
            }
        }

    }

    if (transferNativeEnabled) {
        externalNativeBuild.cmake { path = rootProject.file("native/transfer/CMakeLists.txt"); version = "3.22.1" }
        sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/transfer-native-notices").get().asFile)
    }
    sourceSets["main"].kotlin.srcDir(rootProject.file("native/transfer/kotlin"))
    buildTypes {
        release {
            if (transferNativeEnabled) proguardFiles("transfer-native-rules.pro")
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
        buildConfig = true
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

if (transferNotices != null) tasks.named("preBuild") { dependsOn(transferNotices) }

plugins { alias(libs.plugins.android.application) }
val pocBoringSSLSource = providers.gradleProperty("d2faBoringSSLSource").orNull
    ?: error("Pass -Pd2faBoringSSLSource=<exact pinned checkout>")
val pocNotices = tasks.register<Copy>("copyPocLicenseNotices") {
    into(layout.buildDirectory.dir("generated/poc-notices/legal"))
    from(file("../../../../LICENSE")) { rename { "PROJECT-LICENSE" } }
    from(file("$pocBoringSSLSource/LICENSE")) { rename { "BORINGSSL-LICENSE" } }
}
android {
    namespace = "org.desktop2fa.transfer.poc"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "org.desktop2fa.transfer.poc"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-poc"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
        externalNativeBuild.cmake {
            arguments += listOf("-DD2FA_BORINGSSL_SOURCE=$pocBoringSSLSource", "-DD2FA_JNI=ON",
                "-DD2FA_TEST_API=OFF", "-DBUILD_TESTING=OFF", "-DANDROID_STL=c++_static")
            targets += "d2fa_transfer"
        }
    }
    externalNativeBuild.cmake { path = file("../../../transfer/CMakeLists.txt"); version = "3.22.1" }
    sourceSets["main"].kotlin.srcDir("../../../../app/src/main/java/org/desktop2fa/desktop/watch/common")
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/poc-notices").get().asFile)
    buildTypes { getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation(libs.junit)
}

tasks.named("preBuild") { dependsOn(pocNotices) }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.sylestia.toolhubs.datawarden"
    compileSdk = 37
    defaultConfig { applicationId = "com.sylestia.toolhubs.datawarden"; minSdk = 24; targetSdk = 37; versionCode = 1; versionName = "0.1.0" }
    buildFeatures { compose = true }
    signingConfigs {
        getByName("debug")
        create("debugRelease") { storeFile = rootProject.file(".ci/debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("debugRelease"); isMinifyEnabled = false } }
}
dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.shizuku.api)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sylestia.toolhubs.musicvisualizer"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.sylestia.toolhubs.musicvisualizer"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    // Use the repository release key when CI provides it; keep the debug key as a safe fallback for forks/local PRs.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    val hasReleaseKeystore = !releaseKeystorePath.isNullOrBlank()

    signingConfigs {
        getByName("debug")
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(releaseKeystorePath!!)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
        create("debugRelease") {
            storeFile = rootProject.file(".ci/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debugRelease")
            }
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

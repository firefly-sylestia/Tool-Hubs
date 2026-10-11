plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sylestia.toolhubs.networkswitcher"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.sylestia.toolhubs.networkswitcher"
        minSdk = 26
        targetSdk = 37
        versionCode = 17
        versionName = "0.11.2"
    }
    buildFeatures { compose = true; aidl = true; buildConfig = true }

    // CI decodes the KEYSTORE_BASE64 secret and exports RELEASE_KEYSTORE_PATH; a local release build
    // can point the same variable at a real keystore. When it is absent we fall back to the throwaway
    // debug key so PR builds from forks still work.
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
    implementation(libs.shizuku.provider)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

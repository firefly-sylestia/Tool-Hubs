plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sylestia.toolhubs.wallpaperstudio"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.sylestia.toolhubs.wallpaperstudio"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }
    buildFeatures { compose = true; buildConfig = true }

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
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

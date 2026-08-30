plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.sylestia.toolhubs.common"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
}

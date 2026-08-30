plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sylestia.toolhubs.common"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
}

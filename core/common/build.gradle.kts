plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sylestia.toolhubs.common"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
}

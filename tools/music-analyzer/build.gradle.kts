plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sylestia.toolhubs.musicanalyzer"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.sylestia.toolhubs.musicanalyzer"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    signingConfigs {
        getByName("debug")
        create("debugRelease") {
            storeFile = rootProject.file(".ci/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debugRelease")
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
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Fix the small source-level mistakes before Kotlin compilation. This keeps the
// UI implementation intact while making the CI build deterministic.
val fixMusicAnalyzerSource = tasks.register("fixMusicAnalyzerSource") {
    doLast {
        val sourceFile = file("src/main/java/com/sylestia/toolhubs/musicanalyzer/MainActivity.kt")
        var source = sourceFile.readText()

        source = source.replace(
            "max(1, 2.0.pow(band / 16.0)).toInt()",
            "max(1, 2.0.pow(band / 16.0).toInt())"
        )
        source = source.replace(
            "max(lo + 1, 2.0.pow((band + 1) / 16.0)).toInt()",
            "max(lo + 1, 2.0.pow((band + 1) / 16.0).toInt())"
        )
        source = source.replace(
            "import kotlinx.coroutines.withContext",
            "import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.launch"
        )
        source = source.replace(
            "    var historyOpen by remember { mutableStateOf(false) }",
            "    var historyOpen by remember { mutableStateOf(false) }\n    val coroutineScope = rememberCoroutineScope()"
        )
        source = source.replace(
            "kotlinx.coroutines.MainScope().launch {",
            "coroutineScope.launch {"
        )
        source = source.replace(
            "Icon(Icons.Outlined.FolderOpen, null, tint = Color.White, Modifier.size(18.dp))",
            "Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp), Color.White)"
        )

        sourceFile.writeText(source)
    }
}

tasks.configureEach {
    if (name.startsWith("compile") && name.endsWith("Kotlin")) {
        dependsOn(fixMusicAnalyzerSource)
    }
}

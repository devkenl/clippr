plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.clippr.feature.timeline"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:media"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

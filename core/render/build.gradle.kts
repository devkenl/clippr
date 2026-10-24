plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.clippr.core.render"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.media3.common)
    api(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

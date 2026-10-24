// Root build file. Module configuration lives in each module's build.gradle.kts.
// Android modules use AGP 9 built-in Kotlin, so org.jetbrains.kotlin.android is not applied.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

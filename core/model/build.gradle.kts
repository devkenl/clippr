import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM module: the editing model has no Android or UI dependencies, so it can be
// unit-tested on any JVM and shared by preview, export and persistence.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

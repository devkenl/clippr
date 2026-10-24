# Building Clippr

## Requirements

- JDK 17 or newer (Android Studio's bundled JDK works).
- Android SDK with **Platform 37** and **Build-Tools 36.0.0** (AGP installs missing components
  automatically if the SDK licences are accepted).
- No API keys, accounts or network services are needed beyond the Google and Maven Central
  repositories for dependencies.

## Android Studio

Open the project folder, let Gradle sync, choose the `app` run configuration and a device.

## Command line

```bash
./gradlew :core:model:test          # pure-Kotlin engine tests (no SDK needed)
./gradlew testDebugUnitTest         # all JVM unit tests
./gradlew assembleDebug             # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest # instrumented tests (device or emulator required)
```

## Installing the debug APK

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the device and open it (allow installs from that source when prompted).
The debug build installs as **Clippr** with the application id `app.clippr.debug`, so it can sit
next to a future release build.

## Getting an APK without a local SDK

Push the repository to GitHub. The **CI** workflow (`.github/workflows/ci.yml`) builds the
debug APK and runs the unit tests on every push; download `clippr-debug-apk` from the run's
**Artifacts** section.

## Reproducibility

- All dependency versions are pinned in `gradle/libs.versions.toml`; the Gradle version is
  pinned by the wrapper.
- The APK contains no build timestamp or dependency metadata block (`dependenciesInfo` off).
- Release builds need your own signing configuration; none is committed.

## Running the model tests without Gradle

`core:model` has no dependencies besides the Kotlin standard library and JUnit 4, so its tests
can be compiled with a standalone `kotlinc` and run with `org.junit.runner.JUnitCore` — this is
how they were first verified.

# Dependencies

Versions were checked on 2026-10-24 against official sources. All bundled libraries are
Apache-2.0 licensed and compatible with GPL-3.0-or-later.

| Component | Version | Source checked | Notes |
|---|---|---|---|
| Android Gradle Plugin | 9.4.0 | developer.android.com/build/releases/about-agp | Requires Gradle ≥ 9.6.0; max API 37; built-in Kotlin |
| Gradle | 9.6.1 | Gradle release announcements | Wrapper included |
| Kotlin | 2.4.21 | JetBrains GitHub releases | Compose compiler plugin uses the same version |
| KSP | 2.3.12 | Maven Central listing | KSP2; supports AGP 9 built-in Kotlin since 2.3.1 |
| Compose BOM | 2026.09.00 | developer.android.com/develop/ui/compose/bom | Material 3 |
| AndroidX Media3 | 1.11.1 (stable, 2026-09-10) | Media3 release notes; APIs checked in the 1.11.1 source | transformer, effect, exoplayer, common |
| Room | 2.8.5 (2026-09-09) | Room release notes | KSP, Room Gradle plugin, exported schemas |
| AndroidX Core | 1.19.1 | Core release notes | `core` (the `core-ktx` artifact is now empty) |
| Activity Compose | 1.13.0 | Activity release notes | |
| Lifecycle | 2.11.0 | Lifecycle release notes | runtime-compose, viewmodel-compose |
| kotlinx.coroutines | 1.10.2 | Maven Central | |
| compileSdk / targetSdk | 37 (Android 17) | Android 17 SDK setup page | minSdk 29 (Android 10) |

## Deliberately not used

- **FFmpeg / FFmpegKit.** FFmpegKit was retired and its binaries withdrawn; the brief also asks
  not to use obsolete artifacts. The MVP needs no software codec: Media3 drives the device's
  hardware codecs. If a future feature needs FFmpeg (e.g. exotic import formats), the plan is
  Media3's own `decoder_ffmpeg` extension built from source, with an LGPL-only FFmpeg
  configuration and its licensing documented.
- **Hilt/Dagger.** The dependency graph is a dozen objects; a manual container is clearer and
  avoids an annotation processor in every module.
- **Navigation library.** Three destinations; see `ClipprNavHost`.
- **Analytics, crash reporting, ads, network libraries.** None. The app has no INTERNET
  permission.

## Update policy

Use stable releases only. When updating Media3, re-check `CompositionPlayer` and `Transformer`
behaviour (both are evolving quickly) and run the instrumented export test on a real device.

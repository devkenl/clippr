# Third-party licences

Clippr is distributed under GPL-3.0-or-later. It includes the following libraries, all licensed
under the **Apache License 2.0**, which is compatible with GPLv3 (the Apache-2.0 text is at
https://www.apache.org/licenses/LICENSE-2.0).

| Library | Copyright | Use |
|---|---|---|
| AndroidX Media3 (common, exoplayer, effect, transformer) | The Android Open Source Project | Preview playback, effects, export |
| AndroidX Room | The Android Open Source Project | Project database |
| AndroidX Core, Activity, Lifecycle | The Android Open Source Project | App framework |
| Jetpack Compose (UI, Foundation, Material 3) | The Android Open Source Project | User interface |
| Kotlin standard library | JetBrains s.r.o. and Kotlin contributors | Language runtime |
| kotlinx.coroutines | JetBrains s.r.o. and contributors | Concurrency |

Media3 transitively includes Guava (Apache-2.0) and other Apache-2.0 components; see each
library's published POM for the complete list.

Test-only dependencies (JUnit 4 — EPL-1.0; AndroidX Test — Apache-2.0) are not shipped in the
app.

## Artwork

Clippr's icons, logo and launcher icon are original works created for this project and are
covered by the project licence. No third-party artwork or brand assets are used.

## Codecs and patents

Clippr does not include any audio or video codec implementation. Decoding and encoding (H.264,
AAC and whatever formats the device supports) are performed by the device's platform codecs
through Android's MediaCodec API. Those codecs, and any patent licences they require, are
provided by the device manufacturer. Distributors of builds that add software codecs (for
example FFmpeg) must review the licensing and patent implications of those codecs themselves.

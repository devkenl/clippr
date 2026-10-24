# Clippr implementation plan

This plan was written before implementation began (step A of the project brief) and is kept up to
date as milestones land. The honest per-feature status lives in [FEATURES.md](FEATURES.md).

## Environment findings (2026-10-24)

- The initial workspace contained no repository; Clippr was created from scratch.
- The build sandbox had JDK 21 and Gradle 8.14.3 but **no Android SDK**, and its network policy
  blocked Google Maven, Maven Central, the Gradle distribution server and the Android SDK
  repository. The Android modules therefore could not be compiled in that environment.
- The official Kotlin 2.4.21 compiler (from JetBrains' GitHub releases) *was* reachable, so the
  pure-Kotlin timeline engine (`core:model`) was compiled and its unit tests were run there.
- Dependency versions were verified against official release pages (see
  [DEPENDENCIES.md](DEPENDENCIES.md)) and Media3 APIs against the Media3 1.11.1 source code.

Consequence: the first real Android build happens in CI (GitHub Actions) or on a developer
machine with Android Studio. Expect a short round of compile fixes on that first build.

## Architecture decisions

| Decision | Choice | Why |
|---|---|---|
| Time representation | `Long` microseconds everywhere; `FrameRate` as an exact rational | No float drift; NTSC rates exact; matches Media3's units |
| Edit model | Immutable `Project` values + command objects | Pure, testable, cheap snapshot undo/redo |
| Undo/redo | Snapshot history (`EditHistory`) | Cannot drift like inverse commands; structural sharing keeps it cheap |
| Preview/export parity | One `RenderPlanner` → one `CompositionFactory` used by both `CompositionPlayer` and `Transformer` | What you see is what you export |
| Persistence | Room: summary row + versioned JSON document | Fast project list; forward-compatible format; testable codec in `core:model` |
| Project file format | Hand-written JSON codec in `core:model` | No reflection/codegen; exact 64-bit times; JVM-testable |
| DI | Manual `AppContainer` | Small graph; avoids annotation processing on AGP 9 |
| Navigation | ~100-line host with per-entry `ViewModelStore` | Three destinations; frees the preview player when the editor closes |
| Video engine | Media3 1.11.1 `CompositionPlayer` + `Transformer` | Maintained, hardware-accelerated, Apache-2.0 |
| FFmpeg | Not used | FFmpegKit is retired; the MVP needs no software codec; avoids native/patent burden |
| Kotlin on Android | AGP 9 built-in Kotlin | Current recommended setup; `kotlin-android` plugin not applied |

## Milestones

### M0 — Foundations ✅
- Gradle multi-module project, version catalog, wrapper (Gradle 9.6.1).
- `core:model`: time, project model, commands, placement rules, snapping, history, render planner,
  geometry, audio envelope, JSON codec, integrity checker. **62 unit tests, plus a randomized test
  of 6,000 edits per run (stress-run once at 120,000).**

### M1 — Application shell ✅ (written, not yet compiled)
- Theme and design tokens, original icon set, components.
- Home (projects grid, create / rename / duplicate / delete), editor workspace (tablet and phone
  layouts, focus mode, fullscreen), export screen.

### M2 — Real media handling ✅ (written, not yet compiled)
- SAF import with persisted permissions, metadata probing, thumbnails (memory + disk),
  waveforms (decoded peaks cached on disk), missing-media detection and relinking.
- Room persistence with autosave.

### M3 — Real editing ✅ (written, not yet compiled)
- Timeline: select, move (between compatible tracks), trim, split, delete, snapping, zoom,
  pan, fling, pinch, mouse wheel; undo/redo; keyboard shortcuts.
- Inspector: transform, crop, opacity, speed, volume, mute, fades, text styling.
- Preview: CompositionPlayer, on-canvas transform gestures, guides.

### M4 — Real export ✅ (written, not yet compiled)
- Transformer pipeline (H.264 + AAC MP4), 720p/1080p/4K filtered by encoder capability,
  frame rate and quality options, real progress, cancel, gallery save, share.
- Instrumented end-to-end test that renders an edited timeline and checks output duration.

### M5 — Validation ⏳
- [x] `core:model` compiled and tested in the sandbox.
- [ ] First full Gradle build (CI or Android Studio) and compile fixes.
- [ ] Unit tests via Gradle, instrumented tests on a device or emulator.
- [ ] Manual run of the acceptance flow (see FEATURES.md) on a tablet and a phone.

## Next milestones (after the MVP is verified)

1. **Multi-layer video** — additional video tracks via `MultipleInputVideoGraph` /
   `VideoCompositorSettings`; picture-in-picture.
2. **Keyframes** — reusable `Keyframe<T>` tracks with linear/ease/custom interpolation for
   transform, opacity, volume and effect parameters; time-varying `MatrixTransformation` and
   `GainProvider` already accept presentation time.
3. **Transitions** — cross dissolve and fade-to-black between adjacent clips (requires
   overlapping sequences in the composition).
4. **Color workspace** — exposure/contrast/temperature/tint/saturation via GL shaders,
   curves, lift/gamma/gain, `.cube` LUT import (Media3 `SingleColorLut`), scopes.
5. **Background export** — foreground service (`mediaProcessing` type on Android 15+).
6. **Proxy media** and memory-aware frame caches for 4K-heavy projects.
7. **Drag and drop** from the media panel to the timeline; ripple/roll/slip/slide tools.
8. Light theme, localisation, accessibility audit.

# Clippr

**Create freely. Edit professionally.**

Clippr is a free and open-source video editor for Android, designed for tablets first and
adapted for phones. It aims to be as approachable as a consumer editor while growing into a
professional non-linear editor.

- 100% free and open source (GPL-3.0-or-later)
- Works fully offline; no account, no ads, no watermarks, no telemetry
- Your projects and exports stay on your device; Clippr never modifies your original media
- One optional permission: the microphone, asked for only when you record a voiceover

> **Status: early development (0.2.0).** The editing engine is implemented and unit-tested. The
> app builds in CI and has been used on a Snapdragon tablet. See
> [docs/FEATURES.md](docs/FEATURES.md) for an honest per-feature status.

## What works

- Home: projects, recent exports, device storage, templates, an offline Learn page and Settings
- Projects: create (aspect ratio, resolution, frame rate) or start from a template, rename,
  duplicate, delete, autosave
- Import videos, photos and music; files from cloud apps (Google Drive) are copied to the device;
  thumbnails, waveforms, missing-file detection and relinking
- Keyframes, colour grading and LUTs, 14 stylized effects, voiceover recording
- Timeline: multiple audio tracks, a main video track and a text track; select, move, reorder,
  trim, split, delete, snapping, zoom, pinch, fling, mouse and keyboard support
- Undo/redo for every edit
- Transform (position, scale, rotation, opacity, crop, fit/fill), speed, volume, mute, fades,
  text titles — adjustable in the inspector or directly on the preview
- Export to MP4 (H.264/AAC) at 720p, 1080p or 4K when the device supports it; save to gallery
  and share

## Architecture in one picture

```
Project (immutable) ─ commands ─▶ EditHistory (undo/redo)
        │
        └─ RenderPlanner ─▶ RenderPlan ─ CompositionFactory ─▶ Media3 ─┬─ CompositionPlayer (preview)
                                                                       └─ Transformer (export)
```

Preview and export are built from the same plan, so what you see is what you export.
Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Building

See [docs/BUILDING.md](docs/BUILDING.md). In short: `./gradlew assembleDebug`, then
`adb install -r app/build/outputs/apk/debug/app-debug.apk`. Pushing to GitHub also builds a
downloadable debug APK in CI.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md). The roadmap is in
[docs/ROADMAP.md](docs/ROADMAP.md).

## License

Clippr is licensed under the GNU General Public License v3.0 or later. See [LICENSE](LICENSE)
and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

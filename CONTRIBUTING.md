# Contributing to Clippr

Thanks for helping build a free video editor for Android.

## Ground rules

- **Honesty over polish.** Never present a feature as working unless it is. Unfinished features
  stay out of the UI or are clearly labelled. Update [docs/FEATURES.md](docs/FEATURES.md) in the
  same pull request.
- **Never lose user data.** Project format changes must stay backward compatible
  (`ProjectCodec`), and database changes need an explicit Room migration.
- **No tracking, ads, accounts, network calls or proprietary SDKs.**
- **Small, reviewable pull requests**, each with tests where the code is testable.

## Where code goes

- Editing rules, timing and rendering decisions belong in `core:model` (pure Kotlin), with unit
  tests. If you are writing timeline logic inside a composable, it probably belongs there instead.
- Media3-specific code belongs in `core:render`. Keep Media3 types out of feature modules.
- Features never depend on each other, except the editor composing the timeline and inspector.

## Before opening a pull request

```bash
./gradlew :core:model:test testDebugUnitTest assembleDebug
```

If you touched rendering or export, also run `./gradlew connectedDebugAndroidTest` on a device
and say which device you used.

## Style

- Kotlin official code style (`.editorconfig`). Explicit names; comments explain *why*.
- Times are `Long` microseconds with a `Us` suffix. Never store floating-point seconds.
- UI uses the tokens in `core:ui` (`palette`, `Spacing`, `Radii`) and icons from `ClipprIcons`.
  New icons follow the 24-unit grid, 1.75 stroke, round caps.

## Licensing

By contributing you agree that your contribution is licensed under GPL-3.0-or-later. Only add
dependencies with GPLv3-compatible licences, and record them in THIRD_PARTY_LICENSES.md.

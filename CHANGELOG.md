# Changelog

All notable changes are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic
versioning.

## [Unreleased]

### Fixed
- Preview: dragging, pinching and rotating now work on any visible clip, without first having to
  select it on the timeline with the playhead over it.

### Added
- Clip quick actions: long-press (touch) or right-click (mouse) a clip on the timeline for Split,
  Duplicate, Freeze frame, Delete and Ripple delete.
- Keyframe animation (CapCut-style snapshots of position, scale, rotation and opacity) with
  linear, ease-in, ease-out, smooth and hold easing; volume keyframes.
- Colour: filter presets, exposure, contrast, highlights, shadows, saturation, temperature, tint,
  and `.cube` LUT import — baked into one 3D LUT so preview and export match.
- Effects: blur, vignette, film grain, fade from/to black.
- Text: 12 bundled open-licence fonts, font import, outline, shadow, fade in/out.
- Duplicate clip and freeze frame.

## [0.1.0] — 2026-10-24

### Added
- Editing engine (`core:model`): microsecond time model, exact frame rates, project model,
  command-based edits (insert, move, trim, split, delete, ripple delete, speed, tracks, text),
  non-destructive placement, snapping, snapshot undo/redo, render planner, frame geometry,
  audio envelope, versioned JSON project format, integrity checker, 62 unit tests and a
  randomized edit test.
- Android app (written, not yet compiled on a device): Home, editor (tablet and phone layouts),
  timeline, inspector, preview with on-canvas transforms, media import, thumbnails, waveforms,
  missing-media relinking, Room persistence with autosave, MP4 export with gallery save and
  share, keyboard shortcuts.
- Project infrastructure: Gradle 9.6.1 wrapper, version catalog, CI workflow, issue templates,
  documentation.

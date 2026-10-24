# Changelog

All notable changes are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic
versioning.

## [Unreleased]

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

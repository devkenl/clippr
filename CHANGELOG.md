# Changelog

All notable changes are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic
versioning.

## [Unreleased]

## [0.3.2] — 2026-10-25

### Fixed
- After a 4K 60 fps transition froze the preview, every project (even a new 1080p one with a
  single clip) failed with "the device's video decoder is busy". Cause: two 4K 60 fps clips
  crossing need about twice the decoding power of one, more than many tablets have; the frozen
  player couldn't shut down in time and kept the hardware decoders until the app process ended.
  - The preview now checks what the device's decoders can handle. Transitions it can't play in
    real time are previewed as a cut where the next clip starts, with a note above the preview;
    exports still render the full transition.
  - Edits no longer rebuild the layered preview player each time (that churned through
    decoders); a new player is only made when the number of layers changes.
  - A stalled layered preview pauses with an explanation instead of being rebuilt in a loop.
  - On a decoder error the preview frees the timeline's thumbnail extractors and retries once.
    If the decoders are still taken, the error card offers **Restart Clippr**, which saves,
    restarts the app and reopens the project.

## [0.3.1] — 2026-10-25

### Fixed
- Photos could not be imported at all: the size check treated every image as unreadable (a
  bounds-only decode always returns no bitmap). Images whose size BitmapFactory can't read are
  now measured with ImageDecoder.
- Previewing a project with transitions or layers stopped with "Error creating new EGL surface"
  (video frame processing failed). The preview now gives every new player a fresh surface,
  rebuilds the layered player for each change instead of reusing it, and recovers once by itself
  if the graphics pipeline fails.

### Added
- Snapping in the preview: twisting snaps to straight and 90° angles, pinching snaps to 100 %,
  and dragging snaps to the centre lines, with a light haptic tick and guide lines.

## [0.3.0] — 2026-10-25

### Added
- **Transitions, Vegas Pro style**: drag a clip over the end of the previous clip on a video
  track and the overlap becomes a transition (up to half of either clip). The timeline marks it
  with crossing fade curves; tap it to edit. 17 transitions: cross dissolve, dip to black, push
  and slide (4 directions each), wipe (4 directions), circle, zoom in and zoom out, with smooth,
  linear, ease-in or ease-out motion. Sound crossfades along with the picture. "Add transition"
  from a clip's long-press menu or the new Transition tab.
- **Video layers**: add video tracks (new ones go on top), hide a layer with the eye button,
  add photos or videos as overlays straight from the media panel (picture-in-picture button).
  Clips on upper layers keep all transform, keyframe and effect controls.
- Layout presets for overlays: picture-in-picture in each corner, split screen left/right.
- Chroma key (green or blue screen) with strength, edge softness and spill removal.
- New logo: mark and "clippr." wordmark on Home, in the editor and as the launcher icon.
- "Add track" menu (video layer, audio or text track).

### Changed
- Opacity, fades and transitions on layered timelines are real transparency, composited by
  Media3; single-layer projects render exactly as before.

## [0.2.0] — 2026-10-25

### Added
- **Home 2.0** with navigation across the top: Home (welcome banner, quick actions, recent
  projects, recent exports, device storage), Projects (search, sort, format filter), Media (every
  file used in your projects), Templates, Learn (offline guide, gestures, shortcuts) and Settings.
- Templates: six format presets and eight starter projects with editable, animated titles.
- Recent exports: every export is listed with its size and resolution; play, share, open its
  project, or remove it from the list. Deleted videos are flagged.
- Storage: device free space and what Clippr uses, clear cache, remove unused media copies.
- Settings: defaults for new projects, preview and export quality, cloud-file copying, voiceover
  countdown.
- Voiceover recording in the editor (microphone button or R): the video plays muted while you
  record and the take lands on a free audio track. Asks for microphone access on first use.
- 14 GPU video effects: Sharpen, Glow, Light leak, Zoom blur, Chromatic, Fisheye, Mirror,
  Pixelate, Posterize, Sketch, VHS, Old film, Glitch and Shake, with strength sliders.
- Flip horizontal / vertical.
- Inspector reorganised into tabs (Video, Audio, Speed, Animation, Adjust, Effects; Text and
  Position for titles) with sections closed by default and a dot on sections you've changed.

### Fixed
- Playback ([#1](https://github.com/devkenl/clippr/issues/1)): the play button reacts immediately
  and shows loading; a player error no longer leaves Play dead (Clippr rebuilds the player);
  stalled playback is detected and restarted automatically; errors are shown with Try again.
- Cloud files ([#3](https://github.com/devkenl/clippr/issues/3)): files picked from Google Drive
  and other cloud apps are copied to the device on import, because streamed files stopped
  playing and exporting. Projects with such files offer a one-tap copy.
- Damaged values (for example NaN keyframes) are repaired when a project opens, and a damaged
  clip is left out with a message instead of making the whole project unopenable.
- Export reports why it failed, and stops with an explanation instead of hanging forever if a
  media file stops responding.

### Changed
- Database version 2 (adds export history). Existing projects are migrated, never discarded.

## [0.1.1] — 2026-10-24

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

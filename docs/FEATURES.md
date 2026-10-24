# Feature status

An honest checklist. Status legend:

- ✅ **Tested** — implemented and verified by automated tests that were actually run.
- 🟢 **Builds** — compiles in CI and has been used on a device by the project owner (0.1.0).
- 🟡 **Written** — compiles in CI but has not been tried on a device yet. Treat as unverified.
- ⬜ **Not implemented** — planned; not represented in the UI as working.

## Editing engine (`core:model`)

| Feature | Status |
|---|---|
| Microsecond time model, exact rational frame rates, frame snapping, timecode | ✅ |
| Project / sequence / track / clip / asset model with separate source and timeline time | ✅ |
| Insert, move (incl. between compatible tracks), reorder by dropping | ✅ |
| Trim start/end with source, neighbour and minimum-duration clamping | ✅ |
| Split at time (speed-safe), delete, ripple delete | ✅ |
| Speed change with neighbour push | ✅ |
| Track add/remove, mute, lock (locked tracks reject edits) | ✅ |
| Snapshot undo/redo with labels and history limit | ✅ |
| Snapping to playhead and clip edges | ✅ |
| Render planning (gaps, padding, muting, missing media, fades, text windows) | ✅ |
| Transform geometry (fit/fill, crop, scale, clockwise rotation, position) | ✅ |
| Versioned JSON project format with exact 64-bit times | ✅ |
| Invariant checker + randomized edit testing | ✅ |
| Keyframes (position, scale, rotation, opacity; volume) keyed to source time, with easing | ✅ |
| Colour grade maths (exposure, contrast, highlights, shadows, saturation, temperature, tint, filters) baked to a 3D LUT; `.cube` parser | ✅ |
| Duplicate clip; visual effects and fade settings in the model and project format | ✅ |
| Project repair on open (NaN / out-of-range values, unsorted keyframes); damaged clips left out and reported | ✅ |
| Stylized effects and flips in the model and project format | ✅ |
| Templates (6 formats, 8 starter projects) produce valid projects | ✅ |

## Application (Android)

| Feature | Status |
|---|---|
| Home: project grid, thumbnails, create (name, aspect, resolution, fps), rename, duplicate, delete | 🟢 |
| Home 2.0: top navigation, welcome banner, quick actions, recent exports, storage card | 🟡 |
| Projects page (search, sort, format filter), Media page, Templates page, Learn page | 🟡 |
| Settings: new-project defaults, preview/export quality, cloud copying, voiceover countdown, clear cache, remove unused media | 🟡 |
| Import from cloud apps (Google Drive etc.) copies the file to the device; one-tap copy for older projects | 🟡 |
| Preview reliability: immediate play state, recovery after errors, stall watchdog, error card with Try again | 🟡 |
| Voiceover recording (microphone permission on first use) | 🟡 |
| 14 GPU effects (Sharpen, Glow, Light leak, Zoom blur, Chromatic, Fisheye, Mirror, Pixelate, Posterize, Sketch, VHS, Old film, Glitch, Shake), flip | 🟡 |
| Inspector tabs with collapsible sections | 🟡 |
| Export history and stall detection | 🟡 |
| Room persistence, autosave, save indicator, save on background/close | 🟡 |
| Media import via Storage Access Framework (video, photo, audio), persisted read access | 🟡 |
| Metadata probing; unreadable files reported, not imported | 🟡 |
| Thumbnails (memory + disk cache), audio waveforms (decoded peaks, disk cache) | 🟡 |
| Missing-media detection and relinking | 🟡 |
| Tablet layout: top bar, media panel, preview, inspector, resizable timeline, focus mode | 🟡 |
| Phone layout: preview / timeline / tool drawer, media and inspector as sheets | 🟡 |
| Timeline: ruler, track headers, thumbnails, waveforms, playhead, trim handles, snap line | 🟡 |
| Timeline gestures: tap/shift-tap select, drag/long-press move, trim, pan + fling, pinch zoom, two-finger pan, mouse wheel, Ctrl+wheel zoom, double-tap fit | 🟡 |
| Preview: CompositionPlayer playback, scrubbing, frame step, fullscreen, preview quality | 🟢 |
| Preview overlays: selection box, drag/pinch/rotate the selected clip, double-tap reset, safe areas, 9:16 / 1:1 / 4:5 guides | 🟡 |
| Inspector: position, scale, rotation, opacity, fit/fill, crop, speed presets, volume, mute, fade in/out, text content/size/colour/background/alignment/bold, project format | 🟡 |
| Text overlays (rendered identically in preview and export) | 🟢 |
| 15 fonts (12 bundled OFL families + 3 system), font import (.ttf/.otf), outline, shadow, text fade in/out | 🟡 |
| Preview: touch any visible clip to select it; drag / pinch / rotate it; double-tap resets | 🟡 |
| Timeline: long-press (or right-click) a clip for Split, Duplicate, Freeze frame, Delete, Ripple delete | 🟡 |
| Keyframe UI: add/remove at playhead, previous/next, easing, clear; diamonds on timeline clips | 🟡 |
| Colour section: filters with strength, 7 adjustments, `.cube` LUT import | 🟡 |
| Effects: blur, vignette, film grain; fade from/to black | 🟡 |
| Freeze frame, duplicate (toolbar, drawer, Ctrl+D) | 🟡 |
| Keyboard shortcuts (see below) | 🟡 |
| Export: MP4 (H.264 + AAC), 720p/1080p/4K and 24–60 fps filtered by device encoder support, quality presets, real progress, cancel, save to Movies/Clippr, share, play | 🟡 |

## Not implemented yet

| Feature | Status |
|---|---|
| Multiple overlapping video layers, picture-in-picture | ⬜ (one composited video track in the MVP) |
| Cross-dissolve transitions (dip to black is possible with clip fades) | ⬜ |
| Per-property keyframe curves, speed ramps | ⬜ |
| Colour wheels, curves, scopes (waveform/vectorscope/histogram) | ⬜ |
| Chroma key, masks, blend modes | ⬜ |
| Audio EQ, panning, noise reduction, peak meters | ⬜ |
| Markers, nested sequences, compound clips, adjustment layers | ⬜ |
| Drag and drop from media panel to timeline (tap adds at the playhead instead) | ⬜ |
| Background export with a foreground service | ⬜ (export continues while you stay in the app) |
| Light theme, HEVC/AV1 export | ⬜ |

## Keyboard shortcuts

| Key | Action |
|---|---|
| Space / K | Play / pause |
| S, Ctrl+B | Split at playhead |
| Delete / Backspace | Delete selection (Shift = ripple delete) |
| Ctrl+Z, Ctrl+Shift+Z / Ctrl+Y | Undo, redo |
| ← / → | Previous / next frame (Shift = 1 second) |
| J / L | Back / forward 1 second |
| Home / End | Go to start / end |
| = / − | Zoom timeline in / out |
| \ | Fit timeline |
| Ctrl+I | Import media |
| T | Add title |
| N | Toggle snapping |
| F | Fullscreen preview |
| Ctrl+E | Export |
| Ctrl+D | Duplicate selected clip |
| R | Record voiceover |
| Esc | Clear selection |

## Acceptance flow (to verify on a device)

1. Install Clippr. 2. Launch. 3. Create a project. 4. Import two videos (they are placed on the
timeline automatically when the timeline is empty). 5. See both clips. 6. Play and scrub.
7. Trim (drag handles of a selected clip) and split (S / Split). 8. Move or reorder (drag a
selected clip onto the front half of another). 9. Import music, tap it to add at the playhead,
set its volume in the inspector. 10. Leave and reopen the project. 11. Export to MP4.
12. Reopen the project and confirm the edits are intact.

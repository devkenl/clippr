# Roadmap

What is planned after 0.2.0, as agreed with the project owner. Items are grouped by theme, not
promised for a particular release. Status and honest caveats for shipped features live in
[FEATURES.md](FEATURES.md).

## Next

- **Tablet editor layout** ([#4](https://github.com/devkenl/clippr/issues/4)): a top menu bar with
  workspace tabs (Edit, Color, Audio, Export), a left tool rail and a bottom effects browser.
  The tabbed, collapsible inspector shipped in 0.2.0 is the first part of this.
- **Resizable and movable panels** ([#2](https://github.com/devkenl/clippr/issues/2)): drag panel
  edges, dock panels left/right/bottom, layout presets, reset layout.

## Editing

- Transitions (cross dissolve, wipe, slide) and picture-in-picture / multiple video layers.
  These share one piece of groundwork (overlapping video layers in Media3) and will come together.
- Audio tools: detach audio from video, auto-ducking under voice, EQ presets, loudness normalize.
- Copy and paste attributes (colour, effects, keyframes) between clips; multi-select drag.
- Text animation presets (typewriter, pop, slide), lower thirds, shapes and stickers.
- Canvas background: blur or colour behind letterboxed clips.
- Speed ramps (smooth speed curves) and reverse playback.
- Chroma key (green screen) and simple masks.

## Export and projects

- Export presets (Reels / TikTok / YouTube), HEVC when the device supports it, audio-only export,
  save the current frame as an image.
- Background export with a notification.
- Project backup: export a project as one file to move it to another device.

## Later (needs research)

- **Auto-captions** (on-device speech to text). Kept on the list; needs a capable offline model
  that fits the no-network, no-account rules.

## Decided against

- Ripple / roll / slip / slide trim tools.
- CapCut-style templates with clip slots (simple format and title templates ship instead).
- Proxy media files.
- Cloud sync, accounts, notifications and stock media: Clippr stays offline and local.

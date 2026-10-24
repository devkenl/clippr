# Clippr architecture

## Module map

```
app                      Application, AppContainer (manual DI), navigation host, MainActivity
├─ feature:home          Project gallery, new project sheet, rename/duplicate/delete
├─ feature:editor        Editor workspace, EditorViewModel, preview monitor, media panel
│  ├─ feature:timeline   Timeline composable (ruler, headers, canvas, gestures), viewport
│  └─ feature:inspector  Context-sensitive clip and project controls
├─ feature:export        Export screen, ExportViewModel, app-scoped ExportCoordinator
├─ core:ui               Theme tokens, typography, original icon set, shared components
├─ core:render           Media3 bridge: CompositionFactory, PreviewEngine, ExportEngine, TextRenderer
├─ core:media            Import probing, thumbnails, waveforms, SAF access, gallery publishing
├─ core:database         Room database and ProjectRepository
└─ core:model            Pure Kotlin/JVM editing model (no Android dependencies)
```

`feature:settings` from the original brief is not created yet: there are no app-wide settings in
the MVP (project settings live in the inspector). It will be added when there is something real
to put in it.

Dependency rule: features depend on core modules, never on each other, except that the editor
composes the timeline and inspector, which are stateless UI modules.

## The editing model (`core:model`)

### Time

All timeline and source times are `Long` microseconds. `FrameRate` is an exact rational
(`30000/1001` for 29.97). Frame boundaries are defined so that
`frameIndexAt(frameStartUs(i)) == i` for every frame; this is unit-tested for integer and NTSC
rates. The editor snaps the playhead and split points to frame starts.

### Entities

| Brief | Clippr type | Notes |
|---|---|---|
| Project | `Project` | Settings, assets, sequence, timestamps |
| Sequence | `TimelineSequence` | Renamed to avoid clashing with `kotlin.sequences.Sequence` |
| Track | `Track` | Kind (video/audio/text), mute, lock; clips sorted and non-overlapping |
| Clip | `Clip` | Places a source range at a timeline position, with speed, transform, audio, text |
| MediaAsset | `MediaAsset` | Reference to the user's file (SAF URI); never copied or modified |
| RenderSettings | `ProjectSettings`, `ExportSettings` | Canvas/frame rate; export resolution/quality |
| TimelinePosition | `Long` µs | A wrapper type added no safety for its cost |
| Effect, Keyframe, Transition | — | Planned; deliberately not stubbed until implemented |

### Clip timing semantics

A clip shows source time `[sourceStartUs, sourceEndUs)` starting at `timelineStartUs`. At speed
`s` it lasts `round((sourceEnd − sourceStart) / s)` µs on the timeline, and timeline time `t`
inside the clip shows source time `sourceStart + round((t − timelineStart) × s)`.

- **Trim start** moves the left edge and the source in-point together; the right edge stays put.
- **Trim end** moves the right edge and the source out-point.
- Trims clamp to: the neighbouring clip, the start (0) and end (duration) of the source, and a
  minimum duration of max(one frame, 100 ms). Images and text have no source bounds.
- **Split** at `t` produces two clips that meet exactly (no gap or overlap even with speed
  rounding). Fade-in stays left, fade-out goes right.
- **Move/insert** never destroys content. Dropping onto the first half of a clip inserts before
  it, onto the second half after it; following clips are pushed right only as far as needed,
  and gaps absorb the push.
- **Speed** changes keep the clip start; if the clip grows, later clips are pushed.
- **Locked tracks** reject every command that would change their clips.

Every invariant above is enforced by `ProjectIntegrity` and checked after each step of a
randomized test that applies thousands of random commands, renders, saves and reloads.

### Commands and undo

Edits are `EditCommand`s: pure functions from one immutable `Project` to the next. A command
that cannot apply returns the same project and is not recorded. `EditHistory` stores
`(before, after)` snapshots, so undo is exact by construction. Continuous gestures (slider drags,
on-canvas transforms) are previewed with `EditorViewModel.liveUpdate` and recorded as **one**
step when the gesture ends.

The timeline renders an in-progress drag by applying the real command to the project on every
pointer move, so clamping, pushing and snapping look exactly as they will after release.

## Rendering: one plan for preview and export

```
Project ──RenderPlanner──▶ RenderPlan ──CompositionFactory──▶ Media3 Composition
                (pure Kotlin, tested)          (Android)          ├─▶ CompositionPlayer (preview)
                                                                  └─▶ Transformer (export)
```

`RenderPlanner` decides everything about timing: gaps, padding the video lane to the full
duration, muting, missing media (rendered as gaps so nothing shifts), fade clamping and text
visibility windows. `CompositionFactory` then maps the plan one-to-one onto Media3:

- Sequence 0: the main video track as an audio+video `EditedMediaItemSequence`, with
  `addGap()` for gaps. Items without audio produce silence.
- One audio-only sequence per unmuted audio track.
- Per visual clip: `Crop` → `Presentation` (fit or fill into the canvas) → canvas matrix
  (`FrameGeometry.ndcMatrix`: scale, aspect-corrected clockwise rotation, translation) →
  opacity (`RgbAdjustment`, exact over black because the MVP has one video layer).
- Per clip audio: `GainProcessor` driven by the model's `AudioEnvelope` (volume + linear fades).
- Composition effects: a `Presentation` that pins the output size (Media3 generates gaps as
  16×16 blank frames) and an `OverlayEffect` with one `BitmapOverlay` per text clip. Composition
  effects receive composition-relative timestamps in both the player and Transformer (verified
  in Media3 1.11.1 source), so text appears at the same moments in preview and export.

Preview and export differ only in output size (`PreviewQuality` caps the preview's long side).
Text bitmaps are rendered at the output resolution by the same `TextRenderer`.

### Known Media3 constraints (isolated in `core:render`)

- `CompositionPlayer` is `@ExperimentalApi`. Multi-asset video layouts are not supported by its
  default video graph; hence a single composited video track in the MVP.
- Video crossfades between sequences are not supported yet; transitions are a later milestone.

## Synchronization, frame rates and VFR

- Audio and video of each sequence are kept in sync by Media3's player/renderer clock; the
  sequences of a composition share one timeline.
- `EditedMediaItem.setFrameRate(projectFps)` caps each video item's output frame rate and sets
  the frame rate of images. Sources with a higher frame rate drop frames; lower-rate sources are
  not interpolated (frames repeat).
- Variable-frame-rate sources (typical of phone recordings) are handled by Media3, which uses each
  sample's timestamp; Clippr never assumes a constant source frame rate. Trims operate on time,
  not frame counts, and the editor snaps edit points to the *project* frame grid.
- Images get ms-precision durations (`setImageDurationMs`), so an image clip can differ from its
  µs timeline duration by up to 0.5 ms.

## Persistence

- `projects` table: summary row for the Home screen (name, size, duration, clip count,
  thumbnail path).
- `project_documents` table: the versioned JSON document (`ProjectCodec`, format
  `clippr.project`, schema 1). Readers accept older versions; fields are only ever added;
  unknown enum values degrade to safe defaults. A corrupt document is reported, never silently
  replaced. Room has **no destructive migration fallback**.
- Autosave runs 0.9 s after the last edit and when the editor stops or closes (on an app-scoped
  coroutine so it completes after the screen is gone).

## Threading and resources

- UI and player/transformer calls on the main thread; probing, thumbnails, waveforms, database
  and file work on `Dispatchers.IO` with bounded parallelism (2 thumbnail decoders, 1 waveform
  decoder, at most 3 open `MediaMetadataRetriever`s).
- The timeline canvas never suspends or allocates bitmaps while drawing: missing thumbnails are
  queued and the canvas redraws when they arrive.
- The preview player is released when the editor stops (app backgrounded) and when its screen is
  popped (per-screen `ViewModelStore`), freeing hardware decoders.
- Thumbnail memory cache is 1/8 of the heap and is trimmed on `onTrimMemory`.

## Licensing and patents

Clippr is GPL-3.0-or-later. All bundled dependencies are Apache-2.0 (compatible with GPLv3).
No codec implementations are bundled: H.264/AAC encoding and decoding use the device's platform
codecs via MediaCodec, which are provided (and licensed) by the device maker. See
[THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md).

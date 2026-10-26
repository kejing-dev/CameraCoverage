# Camera Coverage (监控盲区)

Camera-planning app for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space,
passthrough), package `io.embodit.camcoverage`. You mount virtual cameras on your real walls, ceiling,
tables or tripods; each one shows a translucent 3D cone that stops at the first real plane, a footprint on
the surface it lights, and a number tag with its reading. Home mode colours the floor and the walls by
DORI grade and tells you whether you are seen where you stand; workcell mode colours a work area on the
table by how many cameras see each 5 cm cell and shows which camera your body blocks.

Design package (binding): `../spatial-app-factory/apps/cam-coverage/design-package.json` (archetype D,
STAGE_MIXED, single_panel_with_popup, states S01 - S24, rules, panels, entities, palette, copy).
Approved sample (gate B, v2): `../spatial-app-factory/apps/cam-coverage/sample.html`. Skeleton: Theater
Planner `../theater-planner` (itself from Furniture Fit / Spatial Ruler `../spatial-ruler`): panel pool
(PanelRig), Stage root, plane colliders + backstop, world anchors, system-drag conversion (DragLine), lazy
follow, theme and fonts, debug receiver, thumbstick input.

Built by the factory's build station on 2026-10-25 (v0.1.0, debug). Not yet run on the emulator or a
headset: the station only built the APK (see "Not verified").

## Structure

```
app/src/main/java/io/embodit/camcoverage/
  Main.kt                    DefaultStage { CamCoverageTheme { CoverScreen() } }
  domain/                    pure Kotlin
    Vec3.kt Geometry.kt      vectors, quaternions, P2, HeadPose, PanelFacing, PlaneSurface (two-sided ray hits)
    Optics.kt                Lens / Resolution / CamType presets, DORI grades, px/m, ranges
    Camera.kt                Mode, Mount, Camera (mount point, normal, axis, tripod height), Zone, Layout, Aim (limits,
                             lookAt, thumbstick turn + snap angle)
    Coverage.kt              RoomModel (which planes block / take a camera), BodyProxy (head sphere + torso capsule),
                             Frustum, ConeRays (9 x 6 rays stopped by planes), Coverage (floor / wall / zone grids,
                             per-camera stats, the viewer's judgement), Placement (place / slide / zone / knob rules)
    Layout.kt                Settings, SavedLayout, JSON codec (relative to the room anchor), Units
    DragLine.kt              system-drag model (Spatial Ruler round 5c)
  data/Stores.kt             AnchorGateway / LayoutStore interfaces, SharedPreferences store
  platform/                  CcGraph (services), CcLog, HeadTracker (+ debug head injection), ControllerStick,
                             PlaneTracker, WorldAnchorGateway, LaunchActivity, SpatialApplication
  ui/cover/                  CoverUiState (+ designState S01..S24), CoverEvent, CoverViewModel (state machine, undo,
                             anchor + save + restore, coverage passes on Dispatchers.Default), CoverScreen
  ui/cover/scene/            CoverScene (ECS: one registered System; cones, grids, zone, ring, mini room, taps, drags,
                             knob, thumbstick, panel placement), Views (CameraView, ZoneView, BandGrid, YouRingView,
                             PlaneViews), SceneExtras (materials, meshes, MeshBuilder, DynMesh), SceneKit, PanelRig
  ui/cover/components/       SpatialUI panels: ConsolePanel, Cards (camera card, zone card, number tag), Overlays
                             (menu, dialog, snackbar, coachmark), CoverStage (SpatialView + attachments), Strings
  ui/theme/                  CamCoverageTheme (PicoTheme + 4 semantic roles + Noto Sans / Noto Sans SC subset), CcColors
app/src/debug/               DebugCommandReceiver + DebugCommands (QA hooks)
tools/                       make_icons.py (vector icons, launcher, 3D icon layers, 512 export), subset_cjk_font.py
```

One direction of data: panel controls, scene taps / drags, the frame system's conclusions -> `CoverViewModel.onEvent`
-> `CoverUiState` -> `CoverScene.render` (entities, panels). Per-frame work (knob / body drags, thumbstick turn + snap,
the viewer's in-cone judgement with hysteresis, REC lights, the ring, the mini person, lazy follow) lives in
`CoverScene.onFrame` only; the only Compose state it writes is `panelShown`. Coverage grids are computed on
`Dispatchers.Default` (0.2 s after a change, 1 s after planes, <= 5 Hz with the body) and swapped as a whole; each band
mesh is rebuilt only when its cell list changed.

## What the room contains (design `entities3d`)

- Camera (`CameraView`): bullet (wall, home tripod), dome (ceiling), bar + tripod (workcell tripod; long legs on the
  floor, short on a table). Optical centre: 8 cm off a wall, 5 cm under the ceiling, on top of the pole. REC sphere:
  idle 40 %, on when the camera sees you (home) / sees the work area (workcell), 1 Hz blink while your body blocks it.
- Cone: 4 edge strips to the corner hits, a translucent volume (fan from the lens to the 26 outer rays' hits; 10 % green,
  14 % yellow selected, 14 % red blocked, 3.5 % while your head is inside it) and the footprint (8 x 5 cells of the hit
  grid, skipped across plane changes / depth jumps; 1 cm off the surface; fill only in workcell mode, outline always).
  Cones are re-cast when the camera or the planes change; 10 Hz while the knob / stick / drag moves it.
- Aim knob: 5 cm yellow ball 0.6 m along the axis of the selected camera; drag = aim (sphere intersection), limits per
  mount (wall yaw +-80, pitch -85..+10; ceiling <= 85 from down; tripod pitch -85..+30) with a 0.2 s shake at the limit.
- Work area (`ZoneView`): 12 edges, 4 corner cubes (yellow and turning while the anchor is being created), invisible
  hit box (off while placing so that taps reach the table).
- Grids (`BandGrid`): home floor 25 cm cells sampled at 1.6 m (identify / recognize / observe filled, detect outlined,
  blind grey 30 %; 50 cm cells above 1600); home walls 25 cm cells to 2.25 m sampled 3 cm off the wall (blind 18 %);
  workcell 5 cm cells x 3 heights (3+ / 2 / 1 / 0 cameras) with a red outline on cells your body downgrades.
- Your ring (`YouRingView`): 1 m ring under your head: green + n notches = seen by n cameras, grey = blind, red = you
  block a camera (workcell), white 40 % otherwise. The mini person (sphere + box) shares the colour.
- Mini room: a child of the stage root at 1:25 standing on the console's upper edge, room centre over the console;
  faint plane quads, camera markers (tappable = select), the same cone / footprint / grid meshes (one `MeshResource`,
  two `ModelEntity`s), the person. It hides while the menu is open.
- Plane hints: yellow dashed frames on tables (S04), light green dashed frames on every plane while placing (S07).
- Snap target: a 30 cm dashed yellow frame at the auto-aim point (work area centre / your head) while the stick or the
  knob turns a camera; within 3 degrees the stick snaps the axis there (`SNAP` log).

## Interaction (design `rules`, `interactions`; taste profile D)

- Place: [加相机] -> tap a plane: upright = wall mount (axis along the normal, pitched 20 degrees down), CEILING =
  dome (straight down), FLOOR = tripod 1.6 m, other horizontal = desk pole 0.4 m. Landing point clamped 5 cm inside the
  plane. Auto-aim: work area centre (workcell) or your head (home), then clamped. A tap inside the work area's
  footprint on its own table is refused ("that's the work area"). Empty tap / the button again cancels placing.
- Work area: in workcell mode with no area yet, tap a table-like plane (or the floor when there is none): M 60 x 40,
  30 cm high, long edge along the plane's long edge, pushed inside. Tap the hit box = zone card; drag = slide on its
  plane; the card changes size / height (not undoable) and rotates 90 degrees (undoable).
- Select: tap a camera (room or mini room) = camera card beside it, on your side, 25 cm below, clamped to a readable
  height; tap empty space = deselect (with the menu open, the first tap only closes the menu).
- Drag a camera body = slide along its plane (5 cm from the edges; a desk tripod stays out of the work area).
- Thumbstick X turns the selected camera about the vertical (dead zone 0.15, up to 60 deg/s), snaps to the auto-aim
  direction within 3 degrees; the camera card's last line says 「摇杆左右转动」.
- Camera card: lens 2.8 / 4 / 6 / 8 / 12 mm + 2 / 4 / 8 MP (home) or wide / standard / depth / tele (workcell);
  [对准我] / [对准工作区]; tripod height +-10 cm (floor 0.3 - 2.4 m, desk 0.2 - 1.0 m); delete.
- Undo: one stack per mode (20 steps): place, delete, move, aim (knob / aim buttons / stick), lens / res / type,
  place / move / rotate work area.
- You (home): every frame the head (or the injected debug head) is tested against every cone with a 2 degree margin
  in and out and a 0.5 s hold; REC lights, ring, status line follow. Workcell: within 1.5 m of the work area, every
  3 cm of head movement (<= 5 Hz) recomputes the grid with the body proxy; a camera is "blocked" above 20 % lost
  samples and unblocked below 12 %, with a 0.5 s hold.
- Persistence: one world anchor at the floor under the first thing placed; both layouts saved relative to it 0.5 s
  after a change; restore within 10 s of launch (S17) or "last layout isn't here" with [清除] (S18, keeps looking);
  anchor failure = "couldn't save" with Retry (S19). Clearing both modes removes the anchor.

## Panels (all SpatialUI inside PicoTheme; no Material)

Pooled attachment panels, declared once with fixed slot sizes, shown at 0.75 (1 dp = 0.6 mm): `console` 400 x 208
(mode segment + more / verdict line / add camera + undo; lazy follow 18 degrees left, 26 down, 0.6 m), `cameraCard`
400 x 344 slot (content 232 - 344 high), `zoneCard` 360 x 176, `menu` 272 x 200 (above the console), `dialog` 400 x 236
(0.9 m ahead), `snack` 560 x 56 (head-follow 14 degrees up), `coach` 360 x 88 (right of the console), `tag0..tag7`
220 x 36 (pill above each camera; red when blocked, yellow rim when selected, white rim while unsaved / saving). Dialogs
and the snackbar are self-built (`AlertDialog` / `SnackbarHost` do nothing in a Stage). Copy in `res/values` (en) and
`res/values-zh`, read through `rs()` so the in-app language switch works without a restart. Fonts: Noto Sans (Latin,
four weights) + Noto Sans SC subset (273 glyphs; re-run `python tools/subset_cjk_font.py --source
../fire-plan/app/src/main/res/font/noto_sans_sc.ttf` after changing zh copy).

## Debug commands (debug build only)

```
adb -s <serial> shell "am broadcast -a io.embodit.camcoverage.DEBUG -p io.embodit.camcoverage --es cmd NAME [extras]"
```

| cmd | extras | effect |
|---|---|---|
| `state` | `--es s S01..S24` | jump to that design state (seeds an example layout when the mode is empty) |
| `state` | `--es s lost\|blocked\|saving_hold\|anchor_fail\|restoring\|elsewhere\|release` | force a condition (`release` = back to normal) |
| `state` | (none) | print the `STATE` line |
| `seed` | `--es v work\|home` | example layout: workcell = work area on the biggest table + wall / ceiling / desk-depth cameras aimed at it; home = two wall cameras + a dome |
| `mode` | `--es v work\|home` | switch mode |
| `place` | `--es mount wall\|ceiling\|tripod [--ef x --ef y --ef z]` or `--es plane LABEL` | place a camera like a tap in S07 (default: the biggest plane of that kind) |
| `aim` | `[--ei id N] --ef yaw N --ef pitch N` | aim a camera (degrees; yaw 0 = -Z) |
| `lens` | `[--ei id N] --ei lens 0-4 --ei res 0-2` / `--ei type 0-3` | lens / resolution / type |
| `zone` | `[--ei size 0-2 --ei h 0-2] [--es plane LABEL \| --ef x/y/z]` | the work area on that plane (default: the biggest table) |
| `head` | `--ef x --ef y --ef z` / `--es v off` | inject a head position for every judgement (you / body / auto-aim); rendering is untouched |
| `select` `--ei id N` · `zone_select` · `tap` | | select a camera / the work area / tap empty space |
| `stick` | `--es a x,ms` | the thumbstick reads x for ms (emulator QA of the turn + snap) |
| `ui` | `--es s control` | press a control by name (below) |
| `occluders` · `coach_reset` · `lang` · `unit` · `snack s=long\|normal` · `clear [v=both]` | | outlines every ray-blocking plane for 4 s; coachmarks again; settings; clear without the dialog |
| `dump` · `pose` · `panels` · `planes` · `bounds` · `hidemode s=auto\|park` | | reading back |

Control names: `mode_work mode_home more add_camera undo` (console) · `delete lens_2_8 lens_4 lens_6 lens_8 lens_12 res_2
res_4 res_8 type_wide type_standard type_depth type_tele aim_me aim_zone height_minus height_plus` (camera card) ·
`zone_size_S zone_size_M zone_size_L zone_h_20 zone_h_30 zone_h_50 zone_rotate` (zone card) · `lang_system lang_zh
lang_en unit_m unit_ft menu_clear menu_exit` (menu) · `dialog_cancel dialog_confirm blocked_exit blocked_retry` (dialog)
· `snackbar_action` (snack) · `coach_ok` (coach). Plane labels are `SEMANTIC:uuid8` as the `planes` command prints them
(a bare semantic such as `TABLE` picks the first).

Log tag `CamCoverage`: `STATE Sxx` on every state change (mode, planes, selection, coverage shares, the viewer's grade,
blocked set, every camera), `PLACE`, `EDIT`, `DELETE`, `UNDO`, `ZONE`, `SELECT`, `MODE`, `TAP`, `DRAG`, `STICK`, `SNAP`,
`SNACK`, `ANCHOR`, `SAVE`, `RESTORE`, `COVERAGE`, `TRACK`, `CMD`, `DUMP`, `POSE`, `PANEL`, `FRAME` (every 5 s).

## QA screenshot list (design `states[].qaCapture`)

S01 console spinner · S02 scanning snackbar · S03 "finding your last layout" · S04 yellow dashed tables, "tap a table" ·
S05 work area selected + zone card · S06 work area with grey cells, "add a camera" · S07 yellow [取消] button, green
dashed planes · S08 camera 1 selected: yellow cone, knob, card · S09 knob dragged (`state S09` holds the aiming state;
move the knob with the system pointer for the live rebuild) · S10 three cones on the work area, 4-band grid, tags ·
S11 injected head at the table edge: red cone / tag / ring, body outline on the grid · S12 home, no cameras: grey floor
and walls · S13 head inside a cone: green ring with notches, REC on · S14 head behind the camera: grey ring · S15 menu ·
S16 clear dialog · S17 "n cameras restored" · S18 "last layout isn't here" + [清除] · S19 "couldn't save" + [重试] ·
S20 eight cameras, "8/8 full" · S21 tracking lost (grey) · S22 unavailable dialog · S23 anchor held: turning yellow
corners, white tag rims · S24 coachmark right of the console. Console screenshots: take two in a row (the first often
shows the previous frame); un-throttle the idle emulator first (theater-planner `tools/harness/emu.sh fast on`).

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --no-daemon
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> shell monkey -p io.embodit.camcoverage -c android.intent.category.LAUNCHER 1
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src` must be
empty. Icons: `python tools/make_icons.py [export.png]` (vector drawables, launcher foreground, 3D icon layers, the 512
export for the factory).

## Deviations from the design package (recorded in the factory's state.json)

- Blind cells are flat grey (30 % floor, 18 % wall) instead of the hatched texture; the design names this fallback
  (`blindHatch` -> `blindGrey`), the procedural texture is not built in this version.
- The home footprint fill is not drawn on surfaces that carry a coverage grid, as designed; the selected camera's
  footprint outline is yellow (solid, not dashed).
- The camera card is one pooled slot of 400 x 344 dp whose content is 232 / 176 (+56 tripod, +20 hint) high; the
  design's three card heights are the content's, the slot is the tallest.
- The 「摇杆左右转动」 hint sits on the camera card (the console has no spare row in the 208 dp design).

## Emulator findings (emulator-5564, AVD Pico_Emulator_6_1_b, 2026-10-25)

- Installed, launched, all 24 design states captured with `tools/qa_all.sh` (`.scratch/shots/Sxx.png`, copied to
  `../spatial-app-factory/apps/cam-coverage/build/shots/`). Anchors persist across a relaunch (`RESTORE ok`).
- **Mesh creation stalls the idle emulator.** A `MeshResource.createWithMeshModel` waits for the render thread, which
  runs at 1 - 2 fps when the emulator is idle; eight cameras rebuilt at once (3 meshes each + grid bands) blocked the
  main thread for > 10 s = ANR (twice: a state jump with 8 cameras, and a relaunch restoring 8 cameras, "failed to
  complete startup"). The scene therefore queues every cone / grid rebuild (`meshWork`) and paces it by the frame
  interval: two meshes per frame while frames are faster than 40 ms (a headset), one mesh every 2.5 s while they are
  slow (the idle emulator), so the main thread stays free for input and broadcasts; look-only changes go through
  `CameraView.restyle` (materials, no mesh). The live camera (knob / drag / stick) still rebuilds directly at 10 Hz.
  On the idle emulator a layout of 8 cameras therefore takes about a minute to appear in full; `tools/qa_shot.sh`'s
  `fast on` (60 fps for ~10 s) drains the queue at once.
- Two further "isn't responding" dialogs (19:11, 19:26) were **SDK start-up, not the app**: the ANR traces show the
  main thread in `System.loadLibrary` inside `SpatialApp.initialize` (`SpatialApplication.onCreate`), i.e. the arm64
  runtime library being translated by houdini on the x86 emulator while the PC was busy (seven Gradle builds). The
  dialog then stays over every later frame: `tools/qa_full.sh` force-stops, dismisses it with BACK, relaunches with
  `fast on` and only then captures. A stale "isn't responding" dialog survives
  force-stop; `adb shell input keyevent 4` dismisses it (check `dumpsys window windows | grep -c "Not Responding"`).
- This emulator room reports **two BED planes stacked** (frame at 0.58 m, mattress at 0.87 m): a work area placed on
  the lower one is fully occluded by the upper one, so the `seed` picks the TABLE plane nearest to the viewer. Its
  ceiling is 3.3 - 3.7 m high: a ceiling camera over the table is at the 3 m range limit (ceiling planes also stack,
  the seed takes the lowest one above the target). The white vertical ribbons in every screenshot are the room's own
  sheer curtains, not app content.
- The mini room is scaled 1:25 but capped at 26 cm (`MINI_MAX_M`) and stands with its near edge on the console's upper
  edge, the rest behind: at 1:25 this 12 m room would reach the viewer's face.
- `state S11` puts the injected head where the wall camera's ray to the work area passes at chest height, so the torso
  capsule blocks it (tag "1 · 挡住 100%", red ring, red cells).
- Unit tests: `app/src/test/.../CoverageTest.kt` (synthetic table + depth / wall camera; the emulator's plane dump is in
  `app/src/test/resources/planes_emulator_b.txt` for more cases). Run with `gradlew.bat testDebugUnitTest`.
- `tools/qa_full.sh` = the whole pass (clean relaunch, 24 states, launcher evidence, first screen). Not verified: the controller thumbstick (the emulator's controller is DEVICE_NOT_SUPPORTED; `stick` debug command
  only), real pinch drags on the knob / camera / work area, a real headset.

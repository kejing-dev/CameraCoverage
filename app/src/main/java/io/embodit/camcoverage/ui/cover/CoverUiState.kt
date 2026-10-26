package io.embodit.camcoverage.ui.cover

import io.embodit.camcoverage.domain.CamType
import io.embodit.camcoverage.domain.Camera
import io.embodit.camcoverage.domain.CoverageResult
import io.embodit.camcoverage.domain.Dori
import io.embodit.camcoverage.domain.LanguagePref
import io.embodit.camcoverage.domain.Layout
import io.embodit.camcoverage.domain.LengthUnit
import io.embodit.camcoverage.domain.Lens
import io.embodit.camcoverage.domain.Mode
import io.embodit.camcoverage.domain.Resolution
import io.embodit.camcoverage.domain.RoomModel
import io.embodit.camcoverage.domain.Settings
import io.embodit.camcoverage.domain.SurfaceHit
import io.embodit.camcoverage.domain.Vec3
import io.embodit.camcoverage.domain.Zone
import io.embodit.camcoverage.domain.ZoneHeight
import io.embodit.camcoverage.domain.ZoneSize

enum class Phase { STARTING, SCANNING, READY, UNAVAILABLE }

enum class RestoreState { NONE, LOOKING, RESTORED, ELSEWHERE }

/** Snackbar kinds (design `copy.snackbar`, `rules.snackbarPriority`): higher [priority] wins; sticky ones stay until their condition ends. */
enum class SnackKind(val sticky: Boolean, val priority: Int) {
    LOST(true, 5),
    ELSEWHERE(true, 4),
    UNSAVED(true, 3),
    SCANNING(true, 2),
    RESTORED(false, 1),
    LIMIT(false, 1),
    CLEARED(false, 1),
    IN_ZONE(false, 1),
}

data class SnackUi(val kind: SnackKind, val id: Int, val count: Int = 0)

/** The viewer's own judgement in home mode (design `rules.you`), written by the scene's frame system on change only. */
data class YouState(val seenBy: Set<Int> = emptySet(), val pxPerM: Float = 0f, val grade: Dori = Dori.BLIND) {
    val seen: Boolean
        get() = seenBy.isNotEmpty()
}

/**
 * The whole UI state (design `states`, `panels`). Pure data: panels and the scene read it; only the
 * ViewModel writes it.
 */
data class CoverUiState(
    val phase: Phase = Phase.STARTING,
    val room: RoomModel = RoomModel.EMPTY,
    val settings: Settings = Settings(),
    val mode: Mode = Mode.WORKCELL,
    val layouts: Map<Mode, Layout> = emptyMap(),
    val selected: Int? = null,
    val zoneSelected: Boolean = false,
    val placing: Boolean = false,
    /** The camera whose aim knob is being dragged (S09). */
    val aiming: Int? = null,
    /** The camera whose body is being dragged along its plane. */
    val dragging: Int? = null,
    val zoneDragging: Boolean = false,
    val menuOpen: Boolean = false,
    val clearDialog: Boolean = false,
    /** The clear dialog came from "last layout isn't here": both modes go. */
    val clearBoth: Boolean = false,
    val blocked: Boolean = false,
    val snack: SnackUi? = null,
    val coach: Boolean = false,
    val trackingLost: Boolean = false,
    val restore: RestoreState = RestoreState.NONE,
    val unsaved: Boolean = false,
    /** The room anchor is being created (S23). */
    val saving: Boolean = false,
    /** Planes were still arriving within the last 3 s (design `rules.roomPending`). */
    val roomPending: Boolean = false,
    val coverage: CoverageResult? = null,
    val you: YouState = YouState(),
    /** Workcell: the cameras the body blocks (hysteresis applied). */
    val blockedCams: Set<Int> = emptySet(),
    /** Undo steps available per mode (the stacks live in the ViewModel). */
    val undoDepth: Map<Mode, Int> = emptyMap(),
    /** Debug: stay in S01. */
    val holdStarting: Boolean = false,
) {
    val unit: LengthUnit
        get() = settings.unit

    val layout: Layout
        get() = layouts[mode] ?: Layout.EMPTY

    val cameras: List<Camera>
        get() = layout.cameras

    val zone: Zone?
        get() = layout.zone

    val selectedCamera: Camera?
        get() = selected?.let { id -> layout.camera(id) }

    val restoring: Boolean
        get() = restore == RestoreState.LOOKING

    /** The console takes input (design `panels.console.states`). */
    val consoleEnabled: Boolean
        get() = phase == Phase.READY && !trackingLost && !blocked && !restoring

    /** [加相机] can be pressed: also disabled while the last layout is "elsewhere" (S18). */
    val canAdd: Boolean
        get() = consoleEnabled && restore != RestoreState.ELSEWHERE && room.hasAnyMount

    val full: Boolean
        get() = cameras.size >= Layout.MAX_CAMERAS

    val interactive: Boolean
        get() = consoleEnabled && !clearDialog

    val canUndo: Boolean
        get() = (undoDepth[mode] ?: 0) > 0

    val layoutShown: Boolean
        get() = phase == Phase.READY && restore != RestoreState.LOOKING && restore != RestoreState.ELSEWHERE

    val anyPlaced: Boolean
        get() = layouts.values.any { it.cameras.isNotEmpty() || it.zone != null }

    /** The design state the app is in now (design `states`), for the log and the QA station. */
    val designState: String
        get() =
            when {
                blocked || phase == Phase.UNAVAILABLE -> "S22"
                phase == Phase.STARTING -> "S01"
                phase == Phase.SCANNING -> "S02"
                restore == RestoreState.LOOKING -> "S03"
                clearDialog -> "S16"
                restore == RestoreState.ELSEWHERE -> "S18"
                trackingLost -> "S21"
                menuOpen -> "S15"
                unsaved && snack?.kind == SnackKind.UNSAVED -> "S19"
                saving -> "S23"
                restore == RestoreState.RESTORED && snack?.kind == SnackKind.RESTORED -> "S17"
                coach -> "S24"
                placing -> "S07"
                aiming != null -> "S09"
                selected != null -> "S08"
                zoneSelected -> "S05"
                full && snack?.kind == SnackKind.LIMIT -> "S20"
                mode == Mode.WORKCELL && zone == null -> "S04"
                mode == Mode.WORKCELL && cameras.isEmpty() -> "S06"
                mode == Mode.WORKCELL && blockedCams.isNotEmpty() -> "S11"
                mode == Mode.WORKCELL -> "S10"
                cameras.isEmpty() -> "S12"
                you.seen -> "S13"
                else -> "S14"
            }
}

/** Events from the panels, the scene and the platform. */
sealed interface CoverEvent {
    data object SceneReady : CoverEvent

    data class PlanesChanged(val room: RoomModel, val added: Boolean) : CoverEvent

    data class TrackingChanged(val lost: Boolean) : CoverEvent

    data class ModeSelected(val mode: Mode) : CoverEvent

    data object AddCameraClicked : CoverEvent

    /** A plane was tapped (placing a camera or the work area, or a deselect). */
    data class PlaneTapped(val hit: SurfaceHit) : CoverEvent

    data class CameraTapped(val id: Int) : CoverEvent

    data object ZoneTapped : CoverEvent

    data object EmptyTapped : CoverEvent

    data class DragStarted(val id: Int) : CoverEvent

    /** [camera] null: cancelled. */
    data class DragEnded(val id: Int, val camera: Camera?) : CoverEvent

    data class AimStarted(val id: Int) : CoverEvent

    /** [dir] null: cancelled. */
    data class AimEnded(val id: Int, val dir: Vec3?) : CoverEvent

    /** The thumbstick turned the camera about the vertical (taste profile D). */
    data class CameraTurned(val id: Int, val dir: Vec3) : CoverEvent

    data class LensSelected(val lens: Lens) : CoverEvent

    data class ResSelected(val res: Resolution) : CoverEvent

    data class TypeSelected(val type: CamType) : CoverEvent

    data class AimAtMe(val head: Vec3) : CoverEvent

    data object AimAtZone : CoverEvent

    data class HeightStepped(val delta: Int) : CoverEvent

    data object DeleteCamera : CoverEvent

    data object UndoClicked : CoverEvent

    data class ZoneSizeSelected(val size: ZoneSize) : CoverEvent

    data class ZoneHeightSelected(val height: ZoneHeight) : CoverEvent

    data object ZoneRotate : CoverEvent

    data object ZoneDragStarted : CoverEvent

    /** [center] null: cancelled. */
    data class ZoneDragEnded(val center: Vec3?) : CoverEvent

    data object MoreClicked : CoverEvent

    data class LanguageSelected(val language: LanguagePref) : CoverEvent

    data class UnitSelected(val unit: LengthUnit) : CoverEvent

    data object ClearAllClicked : CoverEvent

    data object ClearConfirmed : CoverEvent

    data object DialogDismissed : CoverEvent

    data object ExitClicked : CoverEvent

    data object BlockedExit : CoverEvent

    data object BlockedRetry : CoverEvent

    data object SnackActionClicked : CoverEvent

    data object CoachDismissed : CoverEvent

    /** The scene's frame system saw the viewer's judgement change (home). */
    data class YouChanged(val you: YouState) : CoverEvent

    /** The viewer stands near the work area (<= 1.5 m) and moved: recompute with the body (<= 5 Hz). */
    data class BodyMoved(val head: Vec3, val forward: Vec3) : CoverEvent

    data object BodyLeft : CoverEvent
}

sealed interface CoverEffect {
    data object Exit : CoverEffect

    data class Feedback(val kind: String) : CoverEffect
}

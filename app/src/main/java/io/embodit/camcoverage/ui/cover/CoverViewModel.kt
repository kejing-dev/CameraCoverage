package io.embodit.camcoverage.ui.cover

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.camcoverage.data.AnchorGateway
import io.embodit.camcoverage.data.AnchorOutcome
import io.embodit.camcoverage.data.AnchorRecord
import io.embodit.camcoverage.data.LayoutStore
import io.embodit.camcoverage.domain.Aim
import io.embodit.camcoverage.domain.BodyProxy
import io.embodit.camcoverage.domain.Camera
import io.embodit.camcoverage.domain.Coverage
import io.embodit.camcoverage.domain.Layout
import io.embodit.camcoverage.domain.Mode
import io.embodit.camcoverage.domain.Placement
import io.embodit.camcoverage.domain.RoomModel
import io.embodit.camcoverage.domain.SavedLayout
import io.embodit.camcoverage.domain.Settings
import io.embodit.camcoverage.domain.SurfaceHit
import io.embodit.camcoverage.domain.Vec3
import io.embodit.camcoverage.domain.Zone
import io.embodit.camcoverage.platform.CcGraph
import io.embodit.camcoverage.platform.CcLog
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole behaviour of the app (design-package `rules`, `states`, `interactions`): events from the
 * panels and the scene come in through [onEvent], the state goes out through [state]. The coverage
 * grids are computed on a background dispatcher and replaced as a whole (design `rules.coverage`).
 */
class CoverViewModel(
    private val anchors: AnchorGateway,
    private val store: LayoutStore,
    private val blockReason: () -> String?,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(CoverUiState(settings = store.loadSettings()).let { it.copy(mode = it.settings.lastMode) })
    val state: StateFlow<CoverUiState> = _state

    private val _effects = MutableSharedFlow<CoverEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<CoverEffect> = _effects

    /** Timed snackbars stay 60 s (debug `snack long`). */
    @Volatile var longSnacks = false

    /** Debug: stay in S02 whatever the planes say. */
    @Volatile var debugHoldScanning = false

    /** Debug: keep S23 (the anchor creation never answers) while true. */
    @Volatile var debugHoldSaving = false

    private var anchor: AnchorRecord? = null
    private var savedAnchorId: String? = null
    private val undo = HashMap<Mode, ArrayList<Layout>>()
    private var body: BodyProxy? = null
    private var snackSeq = 0
    private var timedSnackJob: Job? = null
    private var saveJob: Job? = null
    private var restoreJob: Job? = null
    private var blockJob: Job? = null
    private var coachJob: Job? = null
    private var anchorJob: Job? = null
    private var coverageJob: Job? = null
    private var coverageDelayJob: Job? = null
    private var pendingJob: Job? = null
    private var coverageSeq = 0
    private var lastBlockChangeMs = 0L
    private var lastLogged: String? = null
    private var lastLoggedLine: String? = null

    init {
        savedAnchorId = store.savedAnchorId()
        if (savedAnchorId != null) update { it.copy(restore = RestoreState.LOOKING) }
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(e: CoverEvent) {
        val s = _state.value
        when (e) {
            CoverEvent.SceneReady -> onSceneReady()
            is CoverEvent.PlanesChanged -> onPlanes(e.room, e.added)
            is CoverEvent.TrackingChanged -> onTracking(e.lost)
            is CoverEvent.ModeSelected -> if (s.consoleEnabled) switchMode(e.mode)
            CoverEvent.AddCameraClicked -> onAddCamera()
            is CoverEvent.PlaneTapped -> onPlaneTapped(e.hit)
            is CoverEvent.CameraTapped -> onCameraTapped(e.id)
            CoverEvent.ZoneTapped -> onZoneTapped()
            CoverEvent.EmptyTapped -> onEmptyTapped()
            is CoverEvent.DragStarted -> if (s.interactive && !s.menuOpen) update { it.copy(dragging = e.id, selected = e.id, zoneSelected = false, placing = false) }
            is CoverEvent.DragEnded -> onDragEnded(e)
            is CoverEvent.AimStarted -> if (s.interactive && !s.menuOpen) update { it.copy(aiming = e.id, selected = e.id) }
            is CoverEvent.AimEnded -> onAimEnded(e)
            is CoverEvent.CameraTurned -> if (s.interactive) editCamera(e.id, push = true, via = "stick") { it.copy(dir = e.dir) }
            is CoverEvent.LensSelected -> s.selected?.let { id -> editCamera(id, push = true, via = "lens") { it.copy(lens = e.lens) } }
            is CoverEvent.ResSelected -> s.selected?.let { id -> editCamera(id, push = true, via = "res") { it.copy(res = e.res) } }
            is CoverEvent.TypeSelected -> s.selected?.let { id -> editCamera(id, push = true, via = "type") { it.copy(type = e.type) } }
            is CoverEvent.AimAtMe -> s.selected?.let { id -> editCamera(id, push = true, via = "aim-me") { Aim.lookAt(it, e.head) } }
            CoverEvent.AimAtZone -> s.selected?.let { id -> s.zone?.let { z -> editCamera(id, push = true, via = "aim-zone") { Aim.lookAt(it, z.boxCenter) } } }
            is CoverEvent.HeightStepped -> s.selected?.let { id -> editCamera(id, push = false, via = "height") { Placement.stepHeight(it, e.delta) } }
            CoverEvent.DeleteCamera -> s.selected?.let { deleteCamera(it) }
            CoverEvent.UndoClicked -> undoLast()
            is CoverEvent.ZoneSizeSelected -> editZone(push = false) { it.copy(size = e.size) }
            is CoverEvent.ZoneHeightSelected -> editZone(push = false) { it.copy(height = e.height) }
            CoverEvent.ZoneRotate -> editZone(push = true) { it.rotated }
            CoverEvent.ZoneDragStarted -> if (s.interactive && !s.menuOpen) update { it.copy(zoneDragging = true, zoneSelected = true, selected = null) }
            is CoverEvent.ZoneDragEnded -> {
                update { it.copy(zoneDragging = false) }
                e.center?.let { c -> editZone(push = true) { it.copy(center = c) } }
            }
            CoverEvent.MoreClicked -> if (s.consoleEnabled) update { it.copy(menuOpen = !it.menuOpen) }
            is CoverEvent.LanguageSelected -> settings { it.copy(language = e.language) }
            is CoverEvent.UnitSelected -> settings { it.copy(unit = e.unit) }
            CoverEvent.ClearAllClicked -> update { it.copy(menuOpen = false, clearDialog = true, clearBoth = false) }
            CoverEvent.ClearConfirmed -> clear(both = s.clearBoth)
            CoverEvent.DialogDismissed -> update { it.copy(clearDialog = false) }
            CoverEvent.ExitClicked -> {
                update { it.copy(menuOpen = false) }
                _effects.tryEmit(CoverEffect.Exit)
            }
            CoverEvent.BlockedExit -> _effects.tryEmit(CoverEffect.Exit)
            CoverEvent.BlockedRetry -> {
                update { it.copy(blocked = false, phase = Phase.STARTING) }
                onSceneReady()
            }
            CoverEvent.SnackActionClicked -> onSnackAction()
            CoverEvent.CoachDismissed -> {
                settings { it.withCoachSeen(_state.value.mode) }
                update { it.copy(coach = false) }
            }
            is CoverEvent.YouChanged -> if (s.you != e.you) update { it.copy(you = e.you) }
            is CoverEvent.BodyMoved -> onBodyMoved(e.head, e.forward)
            CoverEvent.BodyLeft -> onBodyLeft()
        }
    }

    private fun onSceneReady() {
        val s = _state.value
        if (s.phase != Phase.STARTING) return
        // the head tracker's support state may still be NOT_IN_FULL_SPACE for a moment: give it two seconds
        blockJob?.cancel()
        blockJob =
            scope.launch {
                var reason = blockReason()
                val t0 = SystemClock.elapsedRealtime()
                while (reason != null && SystemClock.elapsedRealtime() - t0 < BLOCK_GRACE_MS) {
                    delay(250)
                    reason = blockReason()
                }
                if (reason != null) {
                    CcLog.i("BLOCKED reason=$reason")
                    update { it.copy(blocked = true, phase = Phase.UNAVAILABLE) }
                    return@launch
                }
                if (_state.value.holdStarting) return@launch
                enterScanning()
            }
    }

    private fun enterScanning() {
        update { it.copy(phase = Phase.SCANNING) }
        showSnack(SnackKind.SCANNING)
        if (savedAnchorId != null) startRestore()
        advanceFromScanning()
    }

    /** Debug: leave S01 now. */
    fun debugLeaveStarting() {
        if (_state.value.phase == Phase.STARTING) {
            update { it.copy(holdStarting = false) }
            enterScanning()
        }
    }

    private fun advanceFromScanning() {
        val s = _state.value
        if (s.phase != Phase.SCANNING || debugHoldScanning) return
        if (!s.room.hasAnyMount) return
        update { it.copy(phase = Phase.READY, snack = if (it.snack?.kind == SnackKind.SCANNING) null else it.snack) }
        scheduleCoverage(COVERAGE_DEBOUNCE_MS)
        maybeCoach()
    }

    private fun onPlanes(room: RoomModel, added: Boolean) {
        update { it.copy(room = room) }
        if (_state.value.phase == Phase.SCANNING) advanceFromScanning()
        if (added) {
            // "still mapping the room" until no plane has arrived for 3 s (design `rules.roomPending`)
            update { it.copy(roomPending = true) }
            pendingJob?.cancel()
            pendingJob =
                scope.launch {
                    delay(ROOM_PENDING_MS)
                    update { it.copy(roomPending = false) }
                }
        }
        if (_state.value.phase == Phase.READY) scheduleCoverage(PLANES_DEBOUNCE_MS)
    }

    private fun onTracking(lost: Boolean) {
        val s = _state.value
        if (lost == s.trackingLost) return
        if (lost) {
            CcLog.i("TRACK lost")
            update { it.copy(trackingLost = true, dragging = null, aiming = null, zoneDragging = false, menuOpen = false, placing = false) }
            showSnack(SnackKind.LOST)
        } else {
            CcLog.i("TRACK recovered")
            update { it.copy(trackingLost = false, snack = if (it.snack?.kind == SnackKind.LOST) null else it.snack) }
        }
    }

    // ---- modes --------------------------------------------------------------------------------

    private fun switchMode(mode: Mode) {
        if (mode == _state.value.mode) return
        update { it.copy(mode = mode, selected = null, zoneSelected = false, placing = false, menuOpen = false, aiming = null, dragging = null, coach = false, you = YouState(), blockedCams = emptySet(), coverage = null) }
        settings { it.copy(lastMode = mode) }
        body = null
        CcLog.i("MODE ${mode.tag}")
        scheduleCoverage(COVERAGE_DEBOUNCE_MS)
        maybeCoach()
    }

    // ---- placing --------------------------------------------------------------------------------

    private fun onAddCamera() {
        val s = _state.value
        if (!s.canAdd) return
        if (s.menuOpen) {
            update { it.copy(menuOpen = false) }
            return
        }
        if (s.placing) {
            update { it.copy(placing = false) }
            return
        }
        if (s.full) {
            showSnack(SnackKind.LIMIT)
            return
        }
        update { it.copy(placing = true, selected = null, zoneSelected = false) }
    }

    private fun onPlaneTapped(hit: SurfaceHit) {
        val s = _state.value
        if (!s.interactive) return
        if (s.menuOpen) {
            update { it.copy(menuOpen = false) }
            return
        }
        when {
            s.placing -> placeCamera(hit)
            s.mode == Mode.WORKCELL && s.zone == null && hit.plane.isHorizontal && hit.plane.semantic != "CEILING" -> {
                // with a table-like plane in the room, the floor only deselects (design `rules.zone`)
                if (hit.plane.semantic == "FLOOR" && s.room.tables.isNotEmpty()) onEmptyTapped() else placeZone(hit)
            }
            else -> onEmptyTapped()
        }
    }

    private fun placeCamera(hit: SurfaceHit) {
        val s = _state.value
        val layout = s.layout
        if (Placement.insideZone(hit, s.zone)) {
            showSnack(SnackKind.IN_ZONE)
            return
        }
        val id = layout.nextId()
        if (id == null) {
            showSnack(SnackKind.LIMIT)
            update { it.copy(placing = false) }
            return
        }
        var cam = Placement.cameraFor(id, hit, s.room, s.mode, s.zone)
        if (cam == null) {
            CcLog.i("PLACE refused plane=${hit.plane.label}")
            return
        }
        // auto-aim (design `rules.autoAim`): the work area's centre, else the viewer's head
        val target = if (s.mode == Mode.WORKCELL) s.zone?.boxCenter ?: headForAim else headForAim
        if (target != null) cam = Aim.lookAt(cam, target)
        val first = layout.cameras.isEmpty()
        push(s.mode)
        update { st -> st.copy(layouts = st.layouts + (st.mode to st.layout.copy(cameras = st.layout.cameras + cam)), placing = false, selected = cam.id, zoneSelected = false) }
        CcLog.i("PLACE id=${cam.id} mount=${cam.mount.tag} on=${hit.plane.label} at=${CcLog.pos(cam.mountPoint)} dir=${CcLog.pos(cam.dir)}")
        afterChange(hit.point)
        if (first) maybeCoach()
        if (_state.value.full) showSnack(SnackKind.LIMIT)
    }

    private fun placeZone(hit: SurfaceHit) {
        val zone = Placement.zoneFor(hit) ?: return
        push(Mode.WORKCELL)
        update { st -> st.copy(layouts = st.layouts + (Mode.WORKCELL to (st.layouts[Mode.WORKCELL] ?: Layout.EMPTY).copy(zone = zone)), zoneSelected = true, selected = null) }
        CcLog.i("ZONE placed at=${CcLog.pos(zone.center)} on=${hit.plane.label}")
        // cameras placed before the zone turn to it
        update { st ->
            val l = st.layout
            st.copy(layouts = st.layouts + (st.mode to l.copy(cameras = l.cameras.map { Aim.lookAt(it, zone.boxCenter) })))
        }
        afterChange(hit.point)
    }

    /** The viewer's head for auto-aim, supplied by the scene through [headProvider]. */
    var headProvider: (() -> Vec3?)? = null
    private val headForAim: Vec3?
        get() = headProvider?.invoke()

    // ---- selection -------------------------------------------------------------------------------

    private fun onCameraTapped(id: Int) {
        val s = _state.value
        if (!s.interactive) return
        if (s.menuOpen) {
            update { it.copy(menuOpen = false) }
            return
        }
        if (s.placing) {
            update { it.copy(placing = false) }
            return
        }
        if (s.layout.camera(id) == null) return
        update { it.copy(selected = id, zoneSelected = false) }
        CcLog.i("SELECT camera id=$id")
    }

    private fun onZoneTapped() {
        val s = _state.value
        if (!s.interactive || s.placing) return
        if (s.menuOpen) {
            update { it.copy(menuOpen = false) }
            return
        }
        if (s.zone == null) return
        update { it.copy(zoneSelected = true, selected = null) }
        CcLog.i("SELECT zone")
    }

    private fun onEmptyTapped() {
        val s = _state.value
        if (s.menuOpen) {
            update { it.copy(menuOpen = false) }
            return
        }
        if (s.placing) {
            update { it.copy(placing = false) }
            return
        }
        if (s.selected != null || s.zoneSelected) update { it.copy(selected = null, zoneSelected = false) }
    }

    // ---- edits -----------------------------------------------------------------------------------

    private fun onDragEnded(e: CoverEvent.DragEnded) {
        val s = _state.value
        update { it.copy(dragging = null) }
        if (s.dragging != e.id || e.camera == null) return
        editCamera(e.id, push = true, via = "drag") { e.camera }
    }

    private fun onAimEnded(e: CoverEvent.AimEnded) {
        val s = _state.value
        update { it.copy(aiming = null) }
        if (s.aiming != e.id || e.dir == null) return
        editCamera(e.id, push = true, via = "aim") { it.copy(dir = Aim.clamp(it, e.dir).first) }
    }

    private fun editCamera(id: Int, push: Boolean, via: String, f: (Camera) -> Camera) {
        val s = _state.value
        if (!s.interactive) return
        val cam = s.layout.camera(id) ?: return
        val next = f(cam)
        if (next == cam) return
        if (push) push(s.mode)
        update { st -> st.copy(layouts = st.layouts + (st.mode to st.layout.with(next)), selected = id, zoneSelected = false) }
        CcLog.i("EDIT id=$id via=$via dir=${CcLog.pos(next.dir)} at=${CcLog.pos(next.mountPoint)} h=${fmt(next.height)} lens=${next.lens.tag} res=${next.res.tag} type=${next.type.tag}")
        afterChange(null)
    }

    private fun editZone(push: Boolean, f: (Zone) -> Zone) {
        val s = _state.value
        if (!s.interactive) return
        val zone = s.zone ?: return
        val next = f(zone)
        if (next == zone) return
        if (push) push(s.mode)
        update { st -> st.copy(layouts = st.layouts + (st.mode to st.layout.copy(zone = next)), zoneSelected = true, selected = null) }
        CcLog.i("ZONE edit at=${CcLog.pos(next.center)} size=${next.size.tag} h=${next.height.tag}")
        afterChange(null)
    }

    private fun deleteCamera(id: Int) {
        val s = _state.value
        if (!s.interactive) return
        if (s.layout.camera(id) == null) return
        push(s.mode)
        update { st -> st.copy(layouts = st.layouts + (st.mode to st.layout.copy(cameras = st.layout.cameras.filter { it.id != id })), selected = null, blockedCams = st.blockedCams - id, you = YouState(st.you.seenBy - id, st.you.pxPerM, st.you.grade)) }
        CcLog.i("DELETE id=$id")
        afterChange(null)
    }

    private fun push(mode: Mode) {
        val stack = undo.getOrPut(mode) { ArrayList() }
        stack += _state.value.layouts[mode] ?: Layout.EMPTY
        while (stack.size > UNDO_MAX) stack.removeAt(0)
        update { it.copy(undoDepth = it.undoDepth + (mode to stack.size)) }
    }

    private fun undoLast() {
        val s = _state.value
        if (!s.consoleEnabled) return
        val stack = undo[s.mode] ?: return
        if (stack.isEmpty()) return
        val prev = stack.removeAt(stack.size - 1)
        update { it.copy(layouts = it.layouts + (it.mode to prev), undoDepth = it.undoDepth + (it.mode to stack.size), selected = null, zoneSelected = false, placing = false) }
        CcLog.i("UNDO mode=${s.mode.tag} cameras=${prev.cameras.size} zone=${prev.zone != null}")
        afterChange(null)
    }

    /** After every layout change: the room anchor on the first placement, the save, the coverage pass. */
    private fun afterChange(firstPoint: Vec3?) {
        if (anchor == null && anchorJob?.isActive != true && _state.value.anyPlaced) {
            val p = firstPoint ?: _state.value.layouts.values.firstNotNullOfOrNull { it.cameras.firstOrNull()?.mountPoint ?: it.zone?.center } ?: Vec3.ZERO
            createAnchor(p)
        } else scheduleSave()
        scheduleCoverage(COVERAGE_DEBOUNCE_MS)
    }

    private fun maybeCoach() {
        val s = _state.value
        if (s.phase != Phase.READY || s.cameras.isEmpty() || s.settings.coachSeen(s.mode) || s.coach) return
        coachJob?.cancel()
        coachJob =
            scope.launch {
                delay(COACH_DELAY_MS)
                val st = _state.value
                if (st.phase == Phase.READY && st.cameras.isNotEmpty() && !st.settings.coachSeen(st.mode)) update { it.copy(coach = true) }
            }
    }

    // ---- body / you ----------------------------------------------------------------------------

    private fun onBodyMoved(head: Vec3, forward: Vec3) {
        val s = _state.value
        if (s.mode != Mode.WORKCELL || s.zone == null || s.cameras.isEmpty()) return
        body = BodyProxy(head, forward)
        scheduleCoverage(0L)
    }

    private fun onBodyLeft() {
        if (body == null) return
        body = null
        scheduleCoverage(0L)
    }

    // ---- coverage -------------------------------------------------------------------------------

    private fun scheduleCoverage(delayMs: Long) {
        coverageDelayJob?.cancel()
        coverageDelayJob =
            scope.launch {
                if (delayMs > 0) delay(delayMs)
                runCoverage()
            }
    }

    private fun runCoverage() {
        val s = _state.value
        if (s.phase != Phase.READY) return
        val seq = ++coverageSeq
        val mode = s.mode
        val layout = s.layout
        val room = s.room
        val proxy = if (mode == Mode.WORKCELL) body else null
        val prevBlocked = s.blockedCams
        coverageJob?.cancel()
        coverageJob =
            scope.launch {
                val t0 = SystemClock.elapsedRealtime()
                val result = withContext(Dispatchers.Default) { Coverage.compute(mode, layout, room, proxy, prevBlocked) }
                if (seq != coverageSeq) return@launch
                val now = SystemClock.elapsedRealtime()
                var blocked = result.blockedCameras
                // a blocked / unblocked verdict holds for at least 0.5 s (design `rules.bodyOcclusion`)
                if (blocked != prevBlocked) {
                    if (now - lastBlockChangeMs < BLOCK_HOLD_MS) blocked = prevBlocked else lastBlockChangeMs = now
                }
                update { it.copy(coverage = result, blockedCams = if (it.mode == mode) blocked else emptySet()) }
                CcLog.i(String.format(Locale.US, "COVERAGE mode=%s floor=%d wall=%d zone=%d blind=%.2f two+=%.2f zero=%d body=%s blocked=%s ms=%d stats=%s", mode.tag, result.floor.size, result.wall.size, result.zone.size, result.blindShare, result.twoPlusShare, result.zeroCells, result.withBody, blocked, now - t0, result.stats.joinToString { st -> String.format(Locale.US, "%d:v=%s d=%s sees=%s blk=%.2f", st.id, st.value?.let { "%.1f".format(Locale.US, it) } ?: "-", st.depthM?.let { "%.2f".format(Locale.US, it) } ?: "-", st.seesZone, st.blockedShare) }))
            }
    }

    // ---- settings, menu, clear ---------------------------------------------------------------

    private fun settings(f: (Settings) -> Settings) {
        val next = f(_state.value.settings)
        store.saveSettings(next)
        update { it.copy(settings = next) }
    }

    private fun clear(both: Boolean) {
        val s = _state.value
        CcLog.i("CLEAR both=$both mode=${s.mode.tag}")
        saveJob?.cancel()
        val layouts = if (both) emptyMap() else s.layouts - s.mode
        undo.remove(s.mode)
        if (both) undo.clear()
        update { it.copy(layouts = layouts, clearDialog = false, clearBoth = false, selected = null, zoneSelected = false, placing = false, coverage = null, blockedCams = emptySet(), you = YouState(), undoDepth = if (both) emptyMap() else it.undoDepth - it.mode, restore = if (both) RestoreState.NONE else it.restore, unsaved = false) }
        val empty = layouts.values.all { it.cameras.isEmpty() && it.zone == null }
        if (empty) {
            val old = anchor
            anchor = null
            savedAnchorId = null
            runCatching { store.saveLayout(null, null) }
            if (old != null) scope.launch { anchors.remove(old.id) }
            if (both) {
                // the saved anchor of the "elsewhere" layout goes too
                val id = savedAnchorId
                if (id != null) scope.launch { anchors.remove(id) }
            }
        } else scheduleSave(now = true)
        showSnack(SnackKind.CLEARED)
        scheduleCoverage(0L)
    }

    private fun onSnackAction() {
        val s = _state.value
        when (s.snack?.kind) {
            SnackKind.ELSEWHERE -> update { it.copy(clearDialog = true, clearBoth = true) }
            SnackKind.UNSAVED -> {
                update { it.copy(snack = null) }
                if (anchor == null) {
                    val p = s.layouts.values.firstNotNullOfOrNull { it.cameras.firstOrNull()?.mountPoint ?: it.zone?.center } ?: Vec3.ZERO
                    createAnchor(p)
                } else scheduleSave(now = true)
            }
            else -> update { it.copy(snack = null) }
        }
    }

    /** Shows [kind] unless a stickier, more important snack is up (design `rules.snackbarPriority`). */
    private fun showSnack(kind: SnackKind, count: Int = 0) {
        val current = _state.value.snack
        if (current != null && current.kind.sticky && current.kind.priority > kind.priority) return
        val snack = SnackUi(kind, ++snackSeq, count)
        update { it.copy(snack = snack) }
        CcLog.i("SNACK ${kind.name}")
        if (!kind.sticky) timedSnack(snack)
    }

    private fun timedSnack(snack: SnackUi) {
        timedSnackJob?.cancel()
        timedSnackJob =
            scope.launch {
                delay(if (longSnacks) 60_000L else SNACK_MS)
                update { if (it.snack?.id == snack.id) it.copy(snack = null) else it }
            }
    }

    // ---- persistence -----------------------------------------------------------------------------

    private fun createAnchor(at: Vec3) {
        anchorJob?.cancel()
        anchorJob =
            scope.launch {
                update { it.copy(saving = true) }
                val floor = _state.value.room.floorY ?: at.y
                val p = Vec3(at.x, floor, at.z)
                while (debugHoldSaving) delay(200)
                val r = anchors.create(p, "cam-coverage-room")
                update { it.copy(saving = false) }
                when (r) {
                    is AnchorOutcome.Ok -> {
                        anchor = r.value
                        savedAnchorId = r.value.id
                        CcLog.i("ANCHOR created id=${r.value.id.take(8)} at=${CcLog.pos(r.value.pose.position)}")
                        if (_state.value.unsaved) update { it.copy(unsaved = false, snack = if (it.snack?.kind == SnackKind.UNSAVED) null else it.snack) }
                        scheduleSave(now = true)
                    }
                    is AnchorOutcome.Failed -> {
                        CcLog.w("ANCHOR create failed code=${r.code} msg=${r.message}")
                        update { it.copy(unsaved = true) }
                        showSnack(SnackKind.UNSAVED)
                    }
                }
            }
    }

    private fun scheduleSave(now: Boolean = false) {
        saveJob?.cancel()
        saveJob =
            scope.launch {
                if (!now) delay(SAVE_DELAY_MS)
                saveNow()
            }
    }

    private fun saveNow() {
        val s = _state.value
        val a = anchor ?: return
        val layout = SavedLayout(a.id, s.layouts.filterValues { it.cameras.isNotEmpty() || it.zone != null })
        val ok = runCatching { store.saveLayout(layout, a.pose) }.isSuccess
        if (ok) {
            if (s.unsaved) update { it.copy(unsaved = false, snack = if (it.snack?.kind == SnackKind.UNSAVED) null else it.snack) }
            CcLog.i("SAVE ok cameras=${layout.layouts.values.sumOf { it.cameras.size }}")
        } else {
            CcLog.w("SAVE failed")
            update { it.copy(unsaved = true) }
            showSnack(SnackKind.UNSAVED)
        }
    }

    /** Restore (design S03 / S17 / S18): the saved anchor must be found within 10 s. */
    private fun startRestore() {
        val id = savedAnchorId ?: return
        restoreJob?.cancel()
        restoreJob =
            scope.launch {
                val t0 = SystemClock.elapsedRealtime()
                var found: AnchorRecord? = null
                while (found == null && SystemClock.elapsedRealtime() - t0 < RESTORE_TIMEOUT_MS) {
                    val r = anchors.loadAll()
                    if (r is AnchorOutcome.Ok) found = r.value.firstOrNull { it.id == id }
                    if (found == null) delay(1000)
                }
                if (found == null) {
                    CcLog.i("RESTORE elsewhere id=${id.take(8)}")
                    update { it.copy(restore = RestoreState.ELSEWHERE) }
                    showSnack(SnackKind.ELSEWHERE)
                    // keep looking in the background (design S18)
                    while (found == null) {
                        delay(2000)
                        val r = anchors.loadAll()
                        if (r is AnchorOutcome.Ok) found = r.value.firstOrNull { it.id == id }
                    }
                }
                restoreFrom(found!!)
            }
    }

    private fun restoreFrom(found: AnchorRecord) {
        anchor = found
        val saved = store.loadLayout(found.pose)
        if (saved == null) {
            update { it.copy(restore = RestoreState.NONE, snack = if (it.snack?.kind == SnackKind.ELSEWHERE) null else it.snack) }
            return
        }
        val n = saved.layouts.values.sumOf { it.cameras.size }
        update { it.copy(layouts = saved.layouts, restore = RestoreState.RESTORED, snack = if (it.snack?.kind == SnackKind.ELSEWHERE) null else it.snack) }
        showSnack(SnackKind.RESTORED, n)
        CcLog.i("RESTORE ok id=${found.id.take(8)} cameras=$n")
        scheduleCoverage(COVERAGE_DEBOUNCE_MS)
        maybeCoach()
    }

    // ---- debug seams (driven by the debug receiver only) --------------------------------------

    fun debugSet(f: (CoverUiState) -> CoverUiState) {
        update(f)
        scheduleCoverage(0L)
    }

    fun debugRestoreTimeout() {
        restoreJob?.cancel()
        update { it.copy(restore = RestoreState.ELSEWHERE, layouts = emptyMap(), coverage = null) }
        showSnack(SnackKind.ELSEWHERE)
    }

    fun debugRestoring() {
        restoreJob?.cancel()
        update { it.copy(restore = RestoreState.LOOKING, selected = null, zoneSelected = false, placing = false, menuOpen = false) }
    }

    fun debugRelease() {
        if (_state.value.restore == RestoreState.LOOKING || _state.value.restore == RestoreState.ELSEWHERE) update { it.copy(restore = RestoreState.NONE, snack = if (it.snack?.kind == SnackKind.ELSEWHERE) null else it.snack) }
        update { it.copy(saving = false, unsaved = false, trackingLost = false, blocked = false, phase = if (it.phase == Phase.UNAVAILABLE) Phase.READY else it.phase, snack = if (it.snack?.kind == SnackKind.UNSAVED || it.snack?.kind == SnackKind.LOST) null else it.snack) }
        scheduleCoverage(0L)
    }

    fun debugCancelCoach() {
        coachJob?.cancel()
        update { it.copy(coach = false) }
    }

    fun debugCoachReset() {
        settings { it.copy(coachSeenWork = false, coachSeenHome = false) }
        maybeCoach()
    }

    fun debugSaveFail() {
        saveJob?.cancel()
        anchorJob?.cancel()
        update { it.copy(unsaved = true, saving = false) }
        showSnack(SnackKind.UNSAVED)
    }

    fun debugSnack(kind: SnackKind, count: Int = 0) = showSnack(kind, count)

    fun debugClearLayouts() {
        undo.clear()
        update { it.copy(layouts = emptyMap(), selected = null, zoneSelected = false, placing = false, coverage = null, blockedCams = emptySet(), you = YouState(), undoDepth = emptyMap()) }
        scheduleCoverage(0L)
    }

    /** Debug: a camera or zone written straight into the layout (seeding). */
    fun debugPut(mode: Mode, f: (Layout) -> Layout) {
        update { it.copy(layouts = it.layouts + (mode to f(it.layouts[mode] ?: Layout.EMPTY))) }
        afterChange(null)
    }

    fun debugBody(head: Vec3?, forward: Vec3) {
        if (head == null) onBodyLeft() else onBodyMoved(head, forward)
    }

    /** `STATE` line for the QA station: the design state and every camera. */
    fun stateLine(): String {
        val s = _state.value
        val sb = StringBuilder()
        sb.append("STATE ").append(s.designState).append(" phase=").append(s.phase).append(" mode=").append(s.mode.tag).append(" planes=").append(s.room.planes.size)
        sb.append(" sel=").append(s.selected ?: "-").append(" zone=").append(s.zone?.let { "${it.size.tag}/${it.height.tag}@${CcLog.pos(it.center)}" } ?: "-")
        s.coverage?.let { c -> sb.append(String.format(Locale.US, " blind=%.2f two+=%.2f zero=%d", c.blindShare, c.twoPlusShare, c.zeroCells)) }
        sb.append(" you=").append(if (s.you.seen) "${s.you.grade} ${s.you.pxPerM.toInt()}" else "blind").append(" blocked=").append(s.blockedCams)
        for (c in s.cameras) {
            val st = s.coverage?.stat(c.id)
            sb.append(String.format(Locale.US, " | %d %s at=%s dir=%s h=%.2f %s %s%s", c.id, c.mount.tag, CcLog.pos(c.position), CcLog.pos(c.dir), c.position.y, if (s.mode == Mode.HOME) "${c.lens.tag}mm/${c.res.tag}MP" else c.type.tag, st?.value?.let { String.format(Locale.US, "v=%.1f", it) } ?: (st?.depthM?.let { String.format(Locale.US, "d=%.2f", it) } ?: ""), if (c.id == s.selected) " *" else ""))
        }
        return sb.toString()
    }

    // =========================================================================================

    private inline fun update(f: (CoverUiState) -> CoverUiState) {
        val next = f(_state.value)
        _state.value = next
        val ds = next.designState
        if (ds != lastLogged) {
            lastLogged = ds
            val line = stateLine()
            if (line != lastLoggedLine) {
                lastLoggedLine = line
                CcLog.i(line)
            }
        }
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext
            return CoverViewModel(CcGraph.anchors, CcGraph.store(app), { CcGraph.head.blockReason() }) as T
        }
    }

    companion object {
        const val BLOCK_GRACE_MS = 2000L
        const val SAVE_DELAY_MS = 500L
        const val RESTORE_TIMEOUT_MS = 10_000L
        const val SNACK_MS = 5000L
        const val COACH_DELAY_MS = 1000L
        const val COVERAGE_DEBOUNCE_MS = 200L
        const val PLANES_DEBOUNCE_MS = 1000L
        const val ROOM_PENDING_MS = 3000L
        const val BLOCK_HOLD_MS = 500L
        const val UNDO_MAX = 20
    }
}

package io.embodit.camcoverage.ui.cover.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.camcoverage.domain.Aim
import io.embodit.camcoverage.domain.Camera
import io.embodit.camcoverage.domain.ConeGeom
import io.embodit.camcoverage.domain.ConeRays
import io.embodit.camcoverage.domain.Coverage
import io.embodit.camcoverage.domain.Dori
import io.embodit.camcoverage.domain.DragLine
import io.embodit.camcoverage.domain.FacePanel
import io.embodit.camcoverage.domain.HeadPose
import io.embodit.camcoverage.domain.Mode
import io.embodit.camcoverage.domain.Mount
import io.embodit.camcoverage.domain.P2
import io.embodit.camcoverage.domain.PanelFacing
import io.embodit.camcoverage.domain.Placement
import io.embodit.camcoverage.domain.PlaneSurface
import io.embodit.camcoverage.domain.Quat4
import io.embodit.camcoverage.domain.RoomModel
import io.embodit.camcoverage.domain.SurfaceHit
import io.embodit.camcoverage.domain.Vec3
import io.embodit.camcoverage.domain.Zone
import io.embodit.camcoverage.platform.CcGraph
import io.embodit.camcoverage.platform.CcLog
import io.embodit.camcoverage.ui.cover.CoverEvent
import io.embodit.camcoverage.ui.cover.CoverUiState
import io.embodit.camcoverage.ui.cover.YouState
import io.embodit.camcoverage.ui.cover.components.PanelSizes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: CoverScene? = null
}

/** The one per-frame ECS system of the app. */
class CoverFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/** Ids of the attachment panels; the Compose side declares exactly these, once. */
object PanelIds {
    const val CONSOLE = "console"
    const val CARD = "cameraCard"
    const val ZONE_CARD = "zoneCard"
    const val MENU = "menu"
    const val DIALOG = "dialog"
    const val SNACK = "snack"
    const val COACH = "coach"
    const val TAG_SLOTS = 8

    fun tag(i: Int) = "tag$i"

    val pool: List<String> = buildList {
        add(CONSOLE); add(CARD); add(ZONE_CARD); add(MENU); add(DIALOG); add(SNACK); add(COACH)
        repeat(TAG_SLOTS) { add(tag(it)) }
    }
}

/**
 * The 3D side of the app: cameras with their cone volumes and footprints, the work area, the coverage
 * grids on floor / walls / work area, the viewer's ring, the 1:25 mini room, the tap and drag targets
 * and the placement of the pre-built panels.
 *
 * Rules (Spatial Ruler / Furniture Fit): one calibrated stage root; Compose state is rendered here
 * through [render] (idempotent); per-frame work happens in [onFrame] and never goes through Compose,
 * except [panelShown]; everything started in [attach] is stopped in [detach]. Main thread only.
 */
class CoverScene(private val sink: (CoverEvent) -> Unit) {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planes = CcGraph.planes
    private val head = CcGraph.head
    private val stick = CcGraph.stick

    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var mini: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var materials: CcMaterials? = null
    private var meshes: CcMeshes? = null
    private var colliders: RoomColliders? = null
    private var scope: CoroutineScope? = null
    private var buildJob: Job? = null
    private var attached = false
    private var built = false
    private var paused = false
    private var ui = CoverUiState()
    private var pose = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var frames = 0L
    private var lastFrameMs = -1L
    private var lastLogMs = 0L
    private var lastTrackingLost = false
    private var lastConsolePressMs = -1L
    private var lastDragEndMs = -1L
    private var planesRev = 0
    private var renderedPlanesRev = -1

    /** Mesh creations waiting for a frame, keyed so that a newer request for the same thing replaces the older. */
    private val meshWork = LinkedHashMap<String, () -> Unit>()

    // views
    private val cameraViews = HashMap<Int, CameraView>()
    private val coneCache = HashMap<Int, Pair<Camera, Int>>()
    private var zoneView: ZoneView? = null
    private var youRing: YouRingView? = null
    private var planeViews: PlaneViews? = null
    private var floorGrid: BandGrid? = null
    private var wallGrid: BandGrid? = null
    private var zoneGrid: BandGrid? = null
    private var snapFrame: Frame? = null

    // live placement (drag / knob / stick), never through Compose state
    private class Drag(val kind: Kind, val id: Int, val press: Vec3) {
        enum class Kind { BODY, KNOB, ZONE }
        var dir: Vec3? = null
        var equivalent = 0f
        var hand = Vec3.ZERO
        var prevHand = Vec3.ZERO
        var totalPx = Vec3.ZERO
        var begun = false
        var moves = 0
        var lastLogMs = 0L
    }

    private class Turn(val id: Int) {
        var neutralSinceMs = -1L
        var frames = 0
    }

    private var drag: Drag? = null
    private var turn: Turn? = null
    private var liveCamera: Camera? = null
    private var liveZone: Zone? = null
    private var liveConeMs = 0L
    private var shakeUntilMs = 0L
    private var snapped = false
    private var awaitCommit: Pair<Int, Long>? = null

    // the viewer's judgement (home) and body sampling (workcell)
    private var youSeen: Set<Int> = emptySet()
    private var youPending: Set<Int>? = null
    private var youPendingSince = 0L
    private var lastYouChangeMs = 0L
    private var lastYou = YouState()
    private var bodySent: Vec3? = null
    private var bodySentMs = 0L
    private var bodyNear = false

    // panel follow
    private val consoleFollow = HorizontalFollow("console", { h -> restAt(h, CONSOLE_LEFT_DEG, -CONSOLE_DOWN_DEG, CONSOLE_M) }, CONSOLE_WINDOW_DEG)
    private val snackFollow = HorizontalFollow("snack", { h -> restAt(h, 0f, gazePitchDeg(h) + SNACK_UP_DEG, SNACK_M) }, 25f)

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity()
        runCatching { root.setName("stageRoot") }
        content.addEntity(root)
        val probe = Entity()
        runCatching { probe.setName("contentProbe") }
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (0.256 m off in z, Spatial Ruler):
        // move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        CcLog.i("STAGE root offset=${CcLog.pos(offset.domain())}")
        val miniRoot = Entity()
        runCatching { miniRoot.setName("miniRoom") }
        root.addChild(miniRoot)
        miniRoot.setLocal(position = Vec3(0f, -30f, 0f), scale = Vec3(MINI_K, MINI_K, MINI_K))
        mini = miniRoot

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.CONSOLE, PanelSizes.CONSOLE_W, PanelSizes.CONSOLE_H, CONSOLE_SCALE)
        rig.world(PanelIds.CARD, PanelSizes.CARD_W, PanelSizes.CARD_H, CARD_SCALE)
        rig.world(PanelIds.ZONE_CARD, PanelSizes.ZONE_W, PanelSizes.ZONE_H, CARD_SCALE)
        rig.world(PanelIds.MENU, PanelSizes.MENU_W, PanelSizes.MENU_H, CONSOLE_SCALE)
        rig.world(PanelIds.DIALOG, PanelSizes.DIALOG_W, PanelSizes.DIALOG_H, DIALOG_SCALE)
        rig.world(PanelIds.SNACK, PanelSizes.SNACK_W, PanelSizes.SNACK_H, SNACK_SCALE)
        rig.world(PanelIds.COACH, PanelSizes.COACH_W, PanelSizes.COACH_H, COACH_SCALE)
        for (i in 0 until PanelIds.TAG_SLOTS) rig.world(PanelIds.tag(i), PanelSizes.TAG_W, PanelSizes.TAG_H, 1f)
        head.start()
        stick.start()
        CcGraph.anchors.start()
        ActiveScene.scene = this
        attached = true
        bindPanels(attachments)
        startSensing()
        buildJob =
            scope?.launch {
                try {
                    build(root, miniRoot, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) CcLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        CcLog.i("SCENE attached")
    }

    /** The meshes, one per yielded frame (a mesh costs about a frame at idle fps), then the fixed views. */
    private suspend fun build(root: Entity, miniRoot: Entity, t0: Long) {
        delay(300)
        val shared = SharedResources()
        this.shared = shared
        val m = CcMaterials(shared)
        materials = m
        yield()
        val meshes = CcMeshes(shared)
        this.meshes = meshes
        yield()
        val colliders = RoomColliders(root)
        colliders.buildBackstop()
        this.colliders = colliders
        colliders.sync(planes.all)
        yield()
        zoneView = ZoneView(root, miniRoot, meshes, m)
        youRing = YouRingView(root, miniRoot, meshes, m)
        planeViews = PlaneViews(root, miniRoot, meshes, m)
        snapFrame = Frame(root, meshes.dashes, m.snapDash, "snapFrame")
        val hosts = listOf(root, miniRoot)
        floorGrid = BandGrid(hosts, Dori.entries.map { m.gradeFill[it]!! }, setOf(Dori.DETECT.ordinal), "floorGrid")
        wallGrid = BandGrid(hosts, Dori.entries.map { if (it == Dori.BLIND) m.wallBlind else m.gradeFill[it]!! }, setOf(Dori.DETECT.ordinal), "wallGrid")
        zoneGrid = BandGrid(hosts, m.countFill, emptySet(), "zoneGrid").also { it.outlineMaterial(m.bodyOutline) }
        yield()
        planeViews?.sync(planes.all) { colliders.fit(it) }
        built = true
        CcLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui)
        sink(CoverEvent.SceneReady)
    }

    private fun startSensing() {
        planes.start {
            val before = colliders?.let { planes.all.size } ?: 0
            colliders?.sync(planes.all)
            planesRev++
            planeViews?.sync(planes.all) { s -> colliders?.fit(s) }
            sink(CoverEvent.PlanesChanged(RoomModel(planes.all.toList()), added = planes.all.size >= before))
        }
    }

    /** The Stage went to the background: stop every tracker (design `container.lifecycle`). */
    fun pause() {
        if (paused || !attached) return
        paused = true
        planes.stop()
        head.stop()
        stick.stop()
        cancelLive()
        CcLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        stick.start()
        startSensing()
        CcLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        meshWork.clear()
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        planes.stop()
        head.stop()
        stick.stop()
        CcGraph.anchors.stop()
        panelShown.clear()
        cameraViews.values.forEach { it.destroy() }
        cameraViews.clear()
        coneCache.clear()
        runCatching { zoneView?.destroy() }
        runCatching { floorGrid?.destroy() }
        runCatching { wallGrid?.destroy() }
        runCatching { zoneGrid?.destroy() }
        runCatching { planeViews?.clear() }
        runCatching { colliders?.clear() }
        colliders = null
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        materials = null
        meshes = null
        zoneView = null
        youRing = null
        planeViews = null
        floorGrid = null
        wallGrid = null
        zoneGrid = null
        snapFrame = null
        stageRoot = null
        mini = null
        contentProbe = null
        rig = null
        content = null
        built = false
        hasHead = false
        drag = null
        turn = null
        liveCamera = null
        liveZone = null
        awaitCommit = null
        youSeen = emptySet()
        youPending = null
        bodySent = null
        bodyNear = false
        consoleFollow.reset()
        snackFollow.reset()
        CcLog.i("SCENE detached")
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: CoverUiState) {
        val before = ui
        ui = state
        val root = stageRoot ?: return
        val miniRoot = mini ?: return
        val rig = rig ?: return
        if (!built) {
            rig[PanelIds.CONSOLE]?.show()
            return
        }
        val m = materials ?: return
        val meshes = meshes ?: return
        val show = state.layoutShown
        val ids = if (show) state.cameras.map { it.id }.toSet() else emptySet()
        for (id in cameraViews.keys.filter { it !in ids }) {
            cameraViews.remove(id)?.destroy()
            coneCache.remove(id)
        }
        if (show) for (c in state.cameras) if (c.id !in cameraViews) cameraViews[c.id] = CameraView(root, miniRoot, meshes, m, c.id).also { it.showAll() }
        if (before.dragging != null && state.dragging == null && drag?.kind == Drag.Kind.BODY) drag = null
        awaitCommit?.let { (id, _) ->
            if (state.layout.camera(id) != before.layout.camera(id)) {
                awaitCommit = null
                if (turn == null && drag == null) clearLive()
            }
        }
        if (before.mode != state.mode) {
            cancelLive()
            youSeen = emptySet()
            youPending = null
        }
        drawStatic(state)
        panels(state)
    }

    /** Everything that only changes with the state: cones (cached by camera + planes), grids, zone, hints. */
    private fun drawStatic(state: CoverUiState) {
        val m = materials ?: return
        val now = SystemClock.elapsedRealtime()
        val show = state.layoutShown
        // cones
        for (c in state.cameras) {
            val v = cameraViews[c.id] ?: continue
            if (!show) {
                v.hideAll()
                continue
            }
            if (liveCamera?.id == c.id) continue
            val cached = coneCache[c.id]
            if (cached == null || cached.first != c || cached.second != planesRev) {
                coneCache[c.id] = c to planesRev
                val id = c.id
                meshWork["cone:$id"] = {
                    val cam = ui.layout.camera(id)
                    val view = cameraViews[id]
                    if (cam != null && view != null && liveCamera?.id != id) view.cone(ConeRays.cast(cam, ui.mode, ui.room), lookOf(id, ui), id in youSeen && ui.mode == Mode.HOME, ui.mode, SystemClock.elapsedRealtime())
                }
            } else if (liveCamera?.id != c.id) {
                // only the look changed: no mesh work
                v.geom?.let { g -> v.restyle(g, lookOf(c.id, state), c.id in youSeen && state.mode == Mode.HOME, state.mode) }
            }
        }
        // grids
        val cov = state.coverage?.takeIf { it.mode == state.mode && show }
        val gridWork = ArrayList<Pair<String, () -> Unit>>()
        if (cov == null || state.mode != Mode.HOME) {
            floorGrid?.let { gridWork += it.planClear() }
            wallGrid?.let { gridWork += it.planClear() }
        } else {
            floorGrid?.let { gridWork += it.plan(cov.floor) }
            wallGrid?.let { gridWork += it.plan(cov.wall) }
        }
        if (cov == null || state.mode != Mode.WORKCELL) zoneGrid?.let { gridWork += it.planClear() }
        else zoneGrid?.let { gridWork += it.plan(cov.zone, cov.zone.filter { it.downgraded }) }
        for ((key, work) in gridWork) meshWork[key] = work
        // zone
        val z = liveZone ?: state.zone
        if (z != null && show && state.mode == Mode.WORKCELL) zoneView?.draw(z, state.saving, hitEnabled = !state.placing, nowMs = now) else zoneView?.hide()
        // plane hints (design S04 yellow dashes on tables, S07 light green on every plane)
        val fitter: (PlaneSurface) -> Triple<Vec3, Vec3, Quat4>? = { s -> colliders?.fit(s) }
        when {
            !show -> planeViews?.hint(emptyList(), m.zoneHint, fitter)
            state.placing -> planeViews?.hint(state.room.planes, m.placeHint, fitter)
            state.mode == Mode.WORKCELL && state.zone == null -> planeViews?.hint(state.room.tables.ifEmpty { state.room.floors }, m.zoneHint, fitter)
            else -> planeViews?.hint(emptyList(), m.zoneHint, fitter)
        }
        renderedPlanesRev = planesRev
    }

    private fun lookOf(id: Int, state: CoverUiState): Look =
        when {
            state.trackingLost -> Look.STALE
            id in state.blockedCams && state.mode == Mode.WORKCELL -> Look.BLOCKED
            id == state.selected || id == state.aiming || id == state.dragging -> Look.SELECTED
            else -> Look.NORMAL
        }

    private fun panels(state: CoverUiState) {
        val rig = rig ?: return
        rig[PanelIds.CONSOLE]?.show()
        if (state.menuOpen) rig[PanelIds.MENU]?.show() else rig[PanelIds.MENU]?.hide()
        val dialogOn = state.clearDialog || state.blocked
        val dialog = rig[PanelIds.DIALOG]
        if (dialogOn && dialog?.shown != true && hasHead) {
            dialog?.show(restAt(pose, 0f, gazePitchDeg(pose), DIALOG_M) + Vec3(0f, -0.05f, 0f))
        } else if (!dialogOn) dialog?.hide()
        if (state.snack != null) rig[PanelIds.SNACK]?.show() else {
            rig[PanelIds.SNACK]?.hide()
            snackFollow.reset()
        }
        val cardOn = state.selectedCamera != null && state.dragging == null && state.aiming == null && state.interactive && state.layoutShown
        if (!cardOn) rig[PanelIds.CARD]?.hide()
        val zoneCardOn = state.zoneSelected && state.zone != null && !state.zoneDragging && state.interactive && state.layoutShown
        if (!zoneCardOn) rig[PanelIds.ZONE_CARD]?.hide()
        if (!(state.coach && !state.menuOpen && !state.clearDialog && !state.trackingLost && !state.blocked)) rig[PanelIds.COACH]?.hide()
        for (i in 0 until PanelIds.TAG_SLOTS) if (!state.layoutShown || i >= state.cameras.size) rig[PanelIds.tag(i)]?.hide()
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        frames++
        head.pose()?.let {
            pose = it
            hasHead = it.position.length > 1e-3f
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        if (!attached || paused) return
        pollTracking(now)
        if (built) {
            if (renderedPlanesRev != planesRev) drawStatic(ui)
            runMeshWork(now)
            stepStick(now)
            drawLive(now)
            judgeYou(now)
            sampleBody(now)
            drawCamerasLive(now)
            drawYou(now)
        }
        if (hasHead) placePanels(now)
        rig.faceViewer(pose.position)
        lastFrameMs = now
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            CcLog.i("FRAME n=$frames head=${CcLog.pos(pose.position)} fwd=${CcLog.pos(pose.forward)} cams=${ui.cameras.size} planes=${planes.all.size} stick=${stick.status()}")
        }
    }

    /**
     * Mesh creations, paced by the frame rate: a mesh blocks the main thread until the render thread has drawn
     * a frame. At a headset's 70 - 90 fps that is nothing and [MESH_PER_FRAME] are built per frame; when frames
     * are slow (the idle emulator at 1 - 2 fps) one mesh every [SLOW_MESH_GAP_MS] keeps the main thread free
     * for input and broadcasts (two ANRs on the emulator before this pacing).
     */
    private fun runMeshWork(now: Long) {
        if (meshWork.isEmpty()) return
        val fast = lastFrameMs > 0 && now - lastFrameMs < FAST_FRAME_MS
        if (!fast && now - lastSlowMeshMs < SLOW_MESH_GAP_MS) return
        var n = 0
        val budget = if (fast) MESH_PER_FRAME else 1
        while (n < budget && meshWork.isNotEmpty()) {
            val key = meshWork.keys.first()
            val work = meshWork.remove(key) ?: continue
            runCatching { work() }.onFailure { CcLog.w("MESH $key failed: $it") }
            n++
        }
        if (!fast) lastSlowMeshMs = now
    }

    private var lastSlowMeshMs = 0L

    private fun pollTracking(now: Long) {
        val lost = head.lost(now)
        if (lost != lastTrackingLost) {
            lastTrackingLost = lost
            sink(CoverEvent.TrackingChanged(lost))
        }
    }

    /** The head used for every judgement: the debug injection wins (design `qaHooks.head`). */
    private fun judgeHead(): Vec3 = head.debugHead ?: pose.position

    private fun cameraOf(id: Int): Camera? = liveCamera?.takeIf { it.id == id } ?: ui.layout.camera(id)

    /** REC lights, looks, knob, snap frame: per frame for every camera. */
    private fun drawCamerasLive(now: Long) {
        val state = ui
        val m = materials ?: return
        if (!state.layoutShown) {
            snapFrame?.hide()
            return
        }
        val cov = state.coverage?.takeIf { it.mode == state.mode }
        val sel = state.dragging ?: state.aiming ?: state.selected
        var snapDrawn = false
        for (c in state.cameras) {
            val v = cameraViews[c.id] ?: continue
            val cam = cameraOf(c.id)!!
            val look = lookOf(c.id, state)
            v.placeBody(cam, state.mode, look, state.room.floorY ?: 0f)
            val seesYou = state.mode == Mode.HOME && c.id in youSeen
            val on = if (state.mode == Mode.HOME) seesYou else (cov?.stat(c.id)?.seesZone == true)
            v.rec(on, blink = c.id in state.blockedCams && state.mode == Mode.WORKCELL, stale = state.trackingLost, nowMs = now)
            val selected = c.id == sel && state.interactive
            v.knob(if (selected) cam else null, now, shake = now < shakeUntilMs)
            if (selected && (turn != null || drag?.kind == Drag.Kind.KNOB)) {
                drawSnapTarget(cam)
                snapDrawn = true
            }
        }
        if (!snapDrawn) snapFrame?.hide()
    }

    /** The dashed target frame where the auto-aim points (taste profile D: snap target). */
    private fun drawSnapTarget(cam: Camera) {
        val f = snapFrame ?: return
        val target = aimTarget() ?: run { f.hide(); return }
        val to = (target - cam.position)
        if (to.length < 0.1f) {
            f.hide()
            return
        }
        val n = -to.normalized()
        val right = Vec3.UP.cross(n).normalized().let { if (it.length < 0.5f) Vec3.RIGHT else it }
        val up = n.cross(right).normalized()
        f.draw(target, right, up, 0.15f, 0.15f, 0.006f, n)
    }

    /** Where a turned camera snaps to: the work area's centre (workcell) or the viewer's head (home). */
    private fun aimTarget(): Vec3? = if (ui.mode == Mode.WORKCELL) ui.zone?.boxCenter else if (hasHead) judgeHead() else null

    /** Home: the viewer in / out of every cone with a 2 degree, 0.5 s hysteresis (design `rules.you`). */
    private fun judgeYou(now: Long) {
        val state = ui
        if (state.mode != Mode.HOME || !state.layoutShown || !hasHead) {
            if (youSeen.isNotEmpty() || lastYou.seen) {
                youSeen = emptySet()
                lastYou = YouState()
                sink(CoverEvent.YouChanged(lastYou))
            }
            return
        }
        val cams = state.cameras
        val h = judgeHead()
        val enter = Coverage.you(cams, state.room, h, -YOU_MARGIN_DEG)
        val stay = Coverage.you(cams, state.room, h, YOU_MARGIN_DEG)
        val next = (youSeen intersect stay.seenBy) union enter.seenBy
        if (next != youSeen) {
            if (youPending != next) {
                youPending = next
                youPendingSince = now
            }
            if (now - lastYouChangeMs >= YOU_HOLD_MS) {
                youSeen = next
                youPending = null
                lastYouChangeMs = now
                // the cone the viewer stands in goes faint (design C6)
                for (c in cams) cameraViews[c.id]?.let { v -> v.geom?.let { g -> v.restyle(g, lookOf(c.id, state), c.id in youSeen, state.mode) } }
            }
        } else youPending = null
        val exact = Coverage.you(cams.filter { it.id in youSeen }, state.room, h, YOU_MARGIN_DEG)
        val you = if (youSeen.isEmpty()) YouState() else YouState(youSeen, exact.pxPerM, exact.grade)
        if (you.seenBy != lastYou.seenBy || you.grade != lastYou.grade || abs(you.pxPerM - lastYou.pxPerM) > 5f) {
            lastYou = you
            sink(CoverEvent.YouChanged(you))
        }
    }

    /** Workcell: the body proxy drives a recompute while the viewer is within 1.5 m of the work area (<= 5 Hz, > 3 cm). */
    private fun sampleBody(now: Long) {
        val state = ui
        val zone = state.zone
        if (state.mode != Mode.WORKCELL || zone == null || state.cameras.isEmpty() || !hasHead || !state.layoutShown) {
            if (bodyNear) {
                bodyNear = false
                bodySent = null
                sink(CoverEvent.BodyLeft)
            }
            return
        }
        val h = judgeHead()
        val near = zone.edgeDistance(h) <= Coverage.BODY_NEAR_M
        if (!near) {
            if (bodyNear) {
                bodyNear = false
                bodySent = null
                sink(CoverEvent.BodyLeft)
            }
            return
        }
        bodyNear = true
        val moved = bodySent?.let { it.distanceTo(h) > BODY_STEP_M } ?: true
        if (moved && now - bodySentMs >= BODY_MIN_MS) {
            bodySent = h
            bodySentMs = now
            sink(CoverEvent.BodyMoved(h, pose.forward))
        }
    }

    /** The viewer's ring and the mini person (design `entities3d.youRing`, `miniRoom`). */
    private fun drawYou(now: Long) {
        val state = ui
        val m = materials ?: return
        val ring = youRing ?: return
        if (!hasHead || !state.layoutShown) {
            ring.hide()
            return
        }
        val h = judgeHead()
        val floorY = state.room.floorY ?: (pose.position.y - 1.6f)
        val mat =
            when {
                state.trackingLost -> m.stale
                state.mode == Mode.HOME -> if (youSeen.isNotEmpty()) m.ringSeen else m.ringBlind
                state.blockedCams.isNotEmpty() -> m.ringBlocked
                else -> m.ringNeutral
            }
        ring.draw(h, floorY, mat, if (state.mode == Mode.HOME) youSeen.size else 0, pose.forward)
    }

    // =========================================================================================
    // thumbstick: turn the selected camera about the vertical, snap at the auto-aim direction
    // =========================================================================================

    private fun turnTarget(): Int? {
        val s = ui
        if (!s.interactive || s.menuOpen || s.placing) return null
        if (drag != null) return null
        return s.selected
    }

    private fun stepStick(now: Long) {
        val dt = if (lastFrameMs < 0) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        val rate = Aim.stickRateDeg(stick.x(now))
        val target = turnTarget()
        turn?.let { t -> if (t.id != target) finishTurn("target") }
        awaitCommit?.let { (_, at) -> if (now - at > COMMIT_WAIT_MS) { awaitCommit = null; if (turn == null && drag == null) clearLive() } }
        if (rate != 0f && target != null) {
            val cam = cameraOf(target) ?: return
            val t = turn ?: Turn(target).also {
                turn = it
                CcLog.i(String.format(Locale.US, "STICK turn start id=%d x=%.2f", target, stick.x(now)))
            }
            t.neutralSinceMs = -1L
            t.frames++
            var (turned, clamped) = Aim.turnAboutVertical(cam, rate * dt)
            if (clamped) shakeUntilMs = now + 200L
            // snap to the auto-aim direction within 3 degrees (taste profile D)
            val tgt = aimTarget()
            if (tgt != null) {
                val want = Aim.lookAt(turned, tgt).dir
                val a = Math.toDegrees(acos(Vec3(want.x, 0f, want.z).normalized().dot(Vec3(turned.dir.x, 0f, turned.dir.z).normalized()).coerceIn(-1f, 1f).toDouble())).toFloat()
                val snap = a <= Aim.SNAP_DEG
                if (snap) {
                    turned = turned.copy(dir = want)
                    if (!snapped) CcLog.i("SNAP id=${turned.id} dir=${CcLog.pos(want)}")
                }
                snapped = snap
            }
            setLive(turned, now)
        } else {
            val t = turn ?: return
            if (t.neutralSinceMs < 0) t.neutralSinceMs = now else if (now - t.neutralSinceMs >= STICK_RELEASE_MS) finishTurn("release")
        }
    }

    private fun finishTurn(why: String) {
        val t = turn ?: return
        turn = null
        val l = liveCamera
        CcLog.i("STICK turn end id=${t.id} frames=${t.frames} why=$why dir=${CcLog.pos(l?.dir)}")
        if (l == null || l.id != t.id) {
            clearLive()
            return
        }
        awaitCommit = t.id to SystemClock.elapsedRealtime()
        sink(CoverEvent.CameraTurned(t.id, l.dir))
    }

    /** Debug `stick`: the stick reads [x] for [ms]. */
    fun debugStick(x: Float, ms: Long): String {
        stick.debugHold(x, ms)
        return String.format(Locale.US, "stick x=%.2f ms=%d target=%s", x, ms, turnTarget() ?: "-")
    }

    // =========================================================================================
    // live placement (shared by drag, knob and stick)
    // =========================================================================================

    private fun setLive(cam: Camera, now: Long) {
        liveCamera = cam
        if (now - liveConeMs >= LIVE_CONE_MS) {
            liveConeMs = now
            cameraViews[cam.id]?.cone(ConeRays.cast(cam, ui.mode, ui.room), lookOf(cam.id, ui), cam.id in youSeen && ui.mode == Mode.HOME, ui.mode, now)
        }
    }

    private fun drawLive(now: Long) {
        // the live camera's cone catches up when its 10 Hz rebuild missed the last change
        val cam = liveCamera ?: return
        if (now - liveConeMs >= LIVE_CONE_MS) setLive(cam, now)
    }

    private fun clearLive() {
        val l = liveCamera
        liveCamera = null
        liveZone = null
        snapped = false
        if (l != null) coneCache.remove(l.id)
        drawStatic(ui)
    }

    private fun cancelLive() {
        if (drag != null) endDrag(cancelled = true)
        turn = null
        clearLive()
    }

    // =========================================================================================
    // panels
    // =========================================================================================

    /** A rest pose from the level heading: [leftDeg] to the left, [upDeg] up, [dist] away. */
    private fun restAt(h: HeadPose, leftDeg: Float, upDeg: Float, dist: Float): Vec3 {
        val f = h.level()
        val l = Math.toRadians(leftDeg.toDouble()).toFloat()
        val u = Math.toRadians(upDeg.coerceIn(-80f, 80f).toDouble()).toFloat()
        val left = P2(f.z, -f.x)
        val d = (f * cos(l) + left * sin(l)).normalized()
        return h.position + d.at(0f) * (dist * cos(u)) + Vec3(0f, dist * sin(u), 0f)
    }

    private fun gazePitchDeg(h: HeadPose): Float = Math.toDegrees(asin(h.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat().coerceIn(-GAZE_PITCH_MAX_DEG, GAZE_PITCH_MAX_DEG)

    private fun placePanels(now: Long) {
        val rig = rig ?: return
        val state = ui
        val console = rig[PanelIds.CONSOLE] ?: return
        val hold = state.menuOpen || state.clearDialog || state.blocked || now - lastConsolePressMs < KEY_HOLD_MS
        consoleFollow.step(pose, now, hold)?.let { console.moveTo(it) }
        val facing = FacePanel.facing(console.position, pose.position, null)
        val cw = PanelSizes.CONSOLE_W * DP_M * CONSOLE_SCALE
        val ch = PanelSizes.CONSOLE_H * DP_M * CONSOLE_SCALE
        if (state.menuOpen) {
            val mw = PanelSizes.MENU_W * DP_M * CONSOLE_SCALE
            val mh = PanelSizes.MENU_H * DP_M * CONSOLE_SCALE
            rig[PanelIds.MENU]?.moveTo(console.position + facing.up * (ch / 2f + mh / 2f + 0.008f) + facing.right * (cw / 2f - mw / 2f) + facing.normal * 0.01f)
        }
        // the mini room stands on the console's upper edge (design `entities3d.miniRoom`), room centre over the console
        mini?.let { mr ->
            val show = built && state.layoutShown && state.room.planes.isNotEmpty() && !state.menuOpen
            if (show) {
                // 1:25, but never wider than MINI_MAX_M: a long room shrinks further so that it stays on the console
                val k = minOf(MINI_K, MINI_MAX_M / maxOf(state.room.extent, 1f))
                // the room's near edge sits on the console's upper edge, the rest extends behind it
                val top = console.position + facing.up * (ch / 2f + MINI_GAP) - facing.normal * (0.02f + state.room.extent * k * 0.5f)
                val c = state.room.center
                mr.setLocal(position = top - c * k, scale = Vec3(k, k, k))
            } else mr.setLocal(position = Vec3(0f, -30f, 0f))
        }
        if (state.snack != null) snackFollow.step(pose, now, hold = false)?.let { rig[PanelIds.SNACK]?.show(it) }
        placeCameraPanels(state, facing, cw, ch, console.position)
    }

    private fun scaleAt(at: Vec3, base: Float): Float = base * (at.distanceTo(pose.position) / 0.8f).coerceIn(0.9f, 3.0f)

    private fun placeCameraPanels(state: CoverUiState, consoleFacing: PanelFacing, cw: Float, ch: Float, consolePos: Vec3) {
        val rig = rig ?: return
        if (!state.layoutShown) {
            rig[PanelIds.CARD]?.hide()
            rig[PanelIds.ZONE_CARD]?.hide()
            rig[PanelIds.COACH]?.hide()
            for (i in 0 until PanelIds.TAG_SLOTS) rig[PanelIds.tag(i)]?.hide()
            return
        }
        val eye = pose.position
        // camera card beside the selected camera, on the viewer's side, 25 cm below, clamped to a readable height
        val sel = state.selectedCamera
        val cardOn = sel != null && state.dragging == null && state.aiming == null && state.interactive
        if (cardOn) {
            val cam = cameraOf(sel!!.id) ?: sel
            val pos = cam.position
            val toward = (eye - pos).rejectFrom(Vec3.UP).normalized().let { if (it.length < 0.5f) Vec3.AHEAD else it }
            var at = pos + toward * 0.12f - Vec3(0f, 0.25f, 0f)
            at = Vec3(at.x, at.y.coerceIn(eye.y - 0.9f, eye.y + 0.1f), at.z)
            val k = scaleAt(at, CARD_SCALE)
            rig[PanelIds.CARD]?.show(at, k, k)
        } else rig[PanelIds.CARD]?.hide()
        // zone card above the near edge of the work area
        val zone = liveZone ?: state.zone
        if (state.zoneSelected && zone != null && !state.zoneDragging && state.interactive) {
            val toward = (eye - zone.center).rejectFrom(Vec3.UP).normalized().let { if (it.length < 0.5f) Vec3.AHEAD else it }
            val l = zone.local(zone.center + toward)
            val edge = if (abs(l.x) * zone.halfD > abs(l.z) * zone.halfW) zone.world(kotlin.math.sign(l.x) * zone.halfW, 0f, zone.top) else zone.world(0f, kotlin.math.sign(l.z) * zone.halfD, zone.top)
            val at = edge + Vec3(0f, 0.15f + PanelSizes.ZONE_H * DP_M * CARD_SCALE / 2f, 0f) + toward * 0.05f
            val k = scaleAt(at, CARD_SCALE)
            rig[PanelIds.ZONE_CARD]?.show(at, k, k)
        } else rig[PanelIds.ZONE_CARD]?.hide()
        // number tags 8 cm above each camera
        val cams = state.cameras
        for (i in 0 until PanelIds.TAG_SLOTS) {
            val c = cams.getOrNull(i)
            if (c == null) {
                rig[PanelIds.tag(i)]?.hide()
                continue
            }
            val cam = cameraOf(c.id) ?: c
            val top = if (cam.mount == Mount.CEILING) cam.position - Vec3(0f, 0.10f, 0f) else cam.position + Vec3(0f, 0.06f + CamDims.TAG_ABOVE, 0f)
            val toward = (eye - top).rejectFrom(Vec3.UP).normalized().let { if (it.length < 0.5f) Vec3.AHEAD else it }
            val at = top + toward * 0.03f
            val k = scaleAt(at, TAG_SCALE)
            rig[PanelIds.tag(i)]?.show(at, k, k)
        }
        // coachmark to the right of the console
        val coachOn = state.coach && !state.menuOpen && !state.clearDialog && !state.trackingLost && !state.blocked
        if (coachOn) {
            val kc = COACH_SCALE
            val w = PanelSizes.COACH_W * DP_M * kc
            rig[PanelIds.COACH]?.show(consolePos + consoleFacing.right * (cw / 2f + w / 2f + 0.015f) + consoleFacing.up * (ch / 2f - PanelSizes.COACH_H * DP_M * kc / 2f), kc, kc)
        } else rig[PanelIds.COACH]?.hide()
    }

    /** The camera of tag slot [i] for the stage (with the live override). */
    fun tagCamera(i: Int, state: CoverUiState): Camera? {
        if (!state.layoutShown) return null
        val c = state.cameras.getOrNull(i) ?: return null
        return cameraOf(c.id) ?: c
    }

    /** A key of the console was pressed: it holds still for a moment (lazy follow). */
    fun onConsolePointer() {
        lastConsolePressMs = SystemClock.elapsedRealtime()
    }

    fun headPose(): HeadPose? = if (hasHead) pose else null

    /** The head for the ViewModel's auto-aim (the debug injection wins). */
    fun headForAim(): Vec3? = if (hasHead || head.debugHead != null) judgeHead() else null

    // =========================================================================================
    // input: system taps and drags
    // =========================================================================================

    private fun cameraOfEntity(e: Entity?): Int? {
        if (e == null) return null
        for ((id, v) in cameraViews) if (v.ownsBody(e)) return id
        return null
    }

    private fun knobOfEntity(e: Entity?): Int? {
        if (e == null) return null
        for ((id, v) in cameraViews) if (v.ownsKnob(e)) return id
        return null
    }

    /** A tap on a plane collider as a surface hit at the tap point, normal towards the viewer. */
    private fun surfaceHit(planeId: String, stage: Vec3?): SurfaceHit? {
        val plane = ui.room.plane(planeId) ?: planes.all.firstOrNull { it.id == planeId } ?: return null
        val p = plane.project(stage ?: plane.centroid)
        var n = plane.normal
        if (n.dot(pose.position - p) < 0f) n = -n
        return SurfaceHit(p, n, pose.position.distanceTo(p), plane)
    }

    fun onSpatialTap(value: SpatialTapValue) {
        val now = SystemClock.elapsedRealtime()
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        if (lastDragEndMs > 0 && now - lastDragEndMs < TAP_AFTER_DRAG_MS) {
            CcLog.i("TAP ignored reason=after-drag target=$name")
            return
        }
        val camId = cameraOfEntity(target) ?: knobOfEntity(target)
        val planeId = target?.let { colliders?.planeIdOf(it) }
        when {
            camId != null -> {
                CcLog.i("TAP camera id=$camId stage=${CcLog.pos(stage)}")
                sink(CoverEvent.CameraTapped(camId))
            }
            target != null && zoneView?.owns(target) == true -> {
                CcLog.i("TAP zone stage=${CcLog.pos(stage)}")
                sink(CoverEvent.ZoneTapped)
            }
            planeId != null -> {
                val hit = surfaceHit(planeId, stage)
                CcLog.i("TAP plane target=$name stage=${CcLog.pos(stage)} hit=${hit != null}")
                if (hit != null) sink(CoverEvent.PlaneTapped(hit)) else sink(CoverEvent.EmptyTapped)
            }
            target != null && colliders?.isRoom(target) == true -> {
                CcLog.i("TAP room target=$name stage=${CcLog.pos(stage)}")
                sink(CoverEvent.EmptyTapped)
            }
            else -> CcLog.i("TAP other target=$name stage=${CcLog.pos(stage)}")
        }
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            "start" -> {
                drag = null
                val target = value.targetEntity
                val name = runCatching { target?.getName() }.getOrNull() ?: "none"
                val press = if (start != null) toStage(start.x, start.y, start.z) else null
                val knob = knobOfEntity(target)
                val body = if (knob == null) cameraOfEntity(target) else null
                val zone = target != null && zoneView?.owns(target) == true
                CcLog.i("DRAG start target=$name knob=${knob ?: "-"} body=${body ?: "-"} zone=$zone stage=${CcLog.pos(press)}")
                if (press == null) return
                if (!ui.interactive || ui.menuOpen || ui.placing) {
                    CcLog.i("DRAG refused state=${ui.designState}")
                    return
                }
                drag =
                    when {
                        knob != null -> Drag(Drag.Kind.KNOB, knob, press)
                        body != null -> Drag(Drag.Kind.BODY, body, press)
                        zone -> Drag(Drag.Kind.ZONE, 0, press)
                        else -> null
                    }
            }
            "move" -> {
                val d = drag ?: return
                val a = value.dragAmount
                d.moves++
                d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
                d.hand = handDelta(d.totalPx) ?: d.hand
                if (!d.begun && d.hand.length >= DRAG_START_M) beginDrag(d)
                if (d.begun) {
                    val dir = d.dir ?: return
                    val turned = DragLine.turn(dir, d.hand - d.prevHand, d.equivalent, pose.right, pose.up, pose.forward)
                    d.prevHand = d.hand
                    d.dir = turned
                    moveDrag(d, turned, now)
                    if (d.moves <= 3 || now - d.lastLogMs > 500) {
                        d.lastLogMs = now
                        CcLog.i("DRAG move kind=${d.kind} id=${d.id} live=${CcLog.pos(liveCamera?.mountPoint ?: liveZone?.center)} dir=${CcLog.pos(liveCamera?.dir)} n=${d.moves}")
                    }
                }
            }
            "end", "cancel" -> {
                val d = drag ?: return
                drag = null
                if (!d.begun) {
                    CcLog.i("DRAG $phase not-begun n=${d.moves}")
                    return
                }
                lastDragEndMs = now
                CcLog.i("DRAG $phase kind=${d.kind} id=${d.id} n=${d.moves}")
                endDrag(cancelled = phase == "cancel", d = d)
            }
        }
    }

    private fun beginDrag(d: Drag) {
        d.begun = true
        val to = d.press - pose.position
        if (to.length < 1e-3f) return
        d.dir = to.normalized()
        val pitch = Math.toDegrees(asin(pose.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        d.equivalent = DragLine.equivalentDepth(to.length, pitch)
        d.prevHand = d.hand
        when (d.kind) {
            Drag.Kind.BODY -> sink(CoverEvent.DragStarted(d.id))
            Drag.Kind.KNOB -> sink(CoverEvent.AimStarted(d.id))
            Drag.Kind.ZONE -> sink(CoverEvent.ZoneDragStarted)
        }
        CcLog.i("DRAG begin kind=${d.kind} id=${d.id} depth=${String.format(Locale.US, "%.2f", d.equivalent)}")
    }

    /** The plane the camera sits on. */
    private fun planeOf(cam: Camera): PlaneSurface? {
        val room = ui.room
        val candidates = room.planes.filter { it.semantic == cam.surface }.ifEmpty { room.planes }
        return candidates.minByOrNull { abs((cam.mountPoint - it.centroid).dot(it.normal)) + (if (it.contains(cam.mountPoint, -0.1f)) 0f else 1f) }
    }

    private fun moveDrag(d: Drag, line: Vec3, now: Long) {
        when (d.kind) {
            Drag.Kind.BODY -> {
                val cam = cameraOf(d.id) ?: return
                val plane = planeOf(cam) ?: return
                val p = Placement.onPlane(plane, pose.position, line) ?: return
                setLive(Placement.slide(cam, plane, p, ui.zone), now)
            }
            Drag.Kind.KNOB -> {
                val cam = cameraOf(d.id) ?: return
                val target = Placement.knobTarget(cam.position, pose.position, line, CamDims.KNOB_M)
                val (dir, clamped) = Aim.clamp(cam, (target - cam.position).normalized())
                if (clamped) shakeUntilMs = now + 200L
                setLive(cam.copy(dir = dir), now)
            }
            Drag.Kind.ZONE -> {
                val zone = liveZone ?: ui.zone ?: return
                val plane = ui.room.planes.filter { it.isHorizontal }.minByOrNull { abs(it.centroid.y - zone.center.y) + (if (it.contains(zone.center, -0.2f)) 0f else 1f) } ?: return
                val p = Placement.onPlane(plane, pose.position, line) ?: return
                val moved = zone.copy(center = Vec3(p.x, zone.center.y, p.z))
                liveZone = moved.copy(center = Placement.fitInside(moved, plane))
                zoneView?.draw(liveZone!!, ui.saving, hitEnabled = true, nowMs = now)
            }
        }
    }

    private fun endDrag(cancelled: Boolean, d: Drag? = null) {
        val dd = d ?: drag
        drag = null
        when (dd?.kind) {
            Drag.Kind.BODY -> sink(CoverEvent.DragEnded(dd.id, if (cancelled) null else liveCamera?.takeIf { it.id == dd.id }))
            Drag.Kind.KNOB -> sink(CoverEvent.AimEnded(dd.id, if (cancelled) null else liveCamera?.takeIf { it.id == dd.id }?.dir))
            Drag.Kind.ZONE -> sink(CoverEvent.ZoneDragEnded(if (cancelled) null else liveZone?.center))
            null -> {
                ui.dragging?.let { sink(CoverEvent.DragEnded(it, null)) }
                ui.aiming?.let { sink(CoverEvent.AimEnded(it, null)) }
                if (ui.zoneDragging) sink(CoverEvent.ZoneDragEnded(null))
            }
        }
        if (dd != null) awaitCommit = dd.id to SystemClock.elapsedRealtime()
        if (cancelled) clearLive()
    }

    /** Debug `aim`: the camera turned to yaw / pitch (degrees) and written at once. */
    fun debugAim(id: Int, yawDeg: Float?, pitchDeg: Float?): String {
        val cam = ui.layout.camera(id) ?: return "no camera $id"
        val currentYaw = Aim.yawDeg(cam.dir)
        val currentPitch = Math.toDegrees(asin(cam.dir.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        val yaw = Math.toRadians((yawDeg ?: currentYaw).toDouble())
        val pitch = Math.toRadians((pitchDeg ?: currentPitch).toDouble())
        val dir = Vec3((sin(yaw) * cos(pitch)).toFloat(), sin(pitch).toFloat(), (-cos(yaw) * cos(pitch)).toFloat())
        sink(CoverEvent.AimStarted(id))
        sink(CoverEvent.AimEnded(id, dir))
        return "aim id=$id dir=${CcLog.pos(dir)}"
    }

    fun debugDragCancel() = cancelLive()

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // debug seams
    // =========================================================================================

    fun debugHideMode(mode: String): String {
        val rig = rig ?: return "no rig"
        rig.hideMode = if (mode == "park") PanelRig.HideMode.PARK else PanelRig.HideMode.AUTO
        return "hidemode=${rig.hideMode}"
    }

    /** Debug `occluders`: every ray-blocking plane outlined for a few seconds. */
    fun debugOccluders(on: Boolean) {
        val m = materials ?: return
        if (on) planeViews?.hint(ui.room.occluders, m.coneEdgeBlocked) { s -> colliders?.fit(s) }
        else drawStatic(ui)
    }

    fun poseLine(): String = "POSE hmd pos=${CcLog.pos(pose.position)} ${CcLog.quat(pose.rotation)} fwd=${CcLog.pos(pose.forward)} valid=$hasHead debugHead=${CcLog.pos(head.debugHead)} youSeen=$youSeen"

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    fun planeLines(): List<String> = planes.all.map { "PLANE ${it.label} center=${CcLog.pos(it.centroid)} normal=${CcLog.pos(it.normal)} area=${String.format(Locale.US, "%.2f", it.area)} verts=${it.vertices.joinToString(" ") { v -> CcLog.pos(v) }}" }

    /** The plane whose label starts with [prefix] (semantic or `SEMANTIC:uuid8`), for debug placement. */
    fun planeByLabel(prefix: String): PlaneSurface? = planes.all.firstOrNull { it.label.startsWith(prefix, ignoreCase = true) } ?: planes.all.firstOrNull { it.semantic.equals(prefix, ignoreCase = true) }

    fun surfaceHitAt(plane: PlaneSurface, point: Vec3?): SurfaceHit = surfaceHit(plane.id, point) ?: SurfaceHit(plane.centroid, plane.normal, 1f, plane)

    companion object {
        const val MINI_K = 0.04f
        const val MINI_GAP = 0.04f
        const val MINI_MAX_M = 0.26f
        const val CONSOLE_SCALE = 0.75f
        const val CARD_SCALE = 0.75f
        const val TAG_SCALE = 0.9f
        const val DIALOG_SCALE = 1.0f
        const val SNACK_SCALE = 1.0f
        const val COACH_SCALE = 0.75f
        const val CONSOLE_LEFT_DEG = 18f
        const val CONSOLE_DOWN_DEG = 26f
        const val CONSOLE_M = 0.6f
        const val CONSOLE_WINDOW_DEG = 45f
        const val SNACK_UP_DEG = 14f
        const val SNACK_M = 0.9f
        const val DIALOG_M = 0.9f
        const val GAZE_PITCH_MAX_DEG = 60f
        const val KEY_HOLD_MS = 600L
        const val DRAG_START_M = 0.01f
        const val TAP_AFTER_DRAG_MS = 400L
        const val STICK_RELEASE_MS = 150L
        const val COMMIT_WAIT_MS = 1500L
        const val LIVE_CONE_MS = 100L
        const val YOU_MARGIN_DEG = 2f
        const val YOU_HOLD_MS = 500L
        const val BODY_STEP_M = 0.03f
        const val BODY_MIN_MS = 200L
        const val LOG_MS = 5000L
        const val MESH_PER_FRAME = 2
        const val FAST_FRAME_MS = 40L
        const val SLOW_MESH_GAP_MS = 2500L
    }
}

/**
 * Lazy follow by heading only (design `panels.console.placement`): the panel rests where it is; once
 * the head's level heading has turned more than [windowDeg] away from the panel for 0.5 s, it slides
 * (0.35 s, ease-in-out cubic) to the rest pose of that moment. Looking up or down never moves it.
 */
internal class HorizontalFollow(private val name: String, private val rest: (HeadPose) -> Vec3, private val windowDeg: Float) {
    private var at: Vec3? = null
    private var from = Vec3.ZERO
    private var to = Vec3.ZERO
    private var slideStartMs = NEVER
    private var outSinceMs = NEVER
    private var lastWritten: Vec3? = null

    fun reset() {
        at = null
        slideStartMs = NEVER
        outSinceMs = NEVER
        lastWritten = null
    }

    fun step(head: HeadPose, nowMs: Long, hold: Boolean): Vec3? {
        val current = at
        if (current == null) {
            val p = rest(head)
            at = p
            CcLog.i("FOLLOW $name park pos=${CcLog.pos(p)}")
            return write(p)
        }
        if (slideStartMs != NEVER) {
            val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val e = if (q < 0.5f) 4f * q * q * q else 1f - (-2f * q + 2f).let { it * it * it } / 2f
            val p = from.lerp(to, e)
            if (q >= 1f) {
                slideStartMs = NEVER
                at = to
            }
            return write(p)
        }
        val toPanel = P2.of(current) - P2.of(head.position)
        val dist = toPanel.length
        val heading = head.level()
        val away = if (dist < 1e-3f) 0f else Math.toDegrees(acos((toPanel.normalized().dot(heading)).coerceIn(-1f, 1f).toDouble())).toFloat()
        val out = away > windowDeg || dist < 0.25f || dist > 1.5f
        if (!out || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            from = current
            to = rest(head)
            slideStartMs = nowMs
            outSinceMs = NEVER
            CcLog.i(String.format(Locale.US, "FOLLOW %s slide angle=%.1f dist=%.2f to=%s", name, away, dist, CcLog.pos(to)))
        }
        return write(current)
    }

    private fun write(p: Vec3): Vec3? {
        if (p == lastWritten) return null
        lastWritten = p
        return p
    }

    companion object {
        private const val NEVER = -1L
        private const val OUT_MS = 500L
        private const val SLIDE_MS = 350f
    }
}

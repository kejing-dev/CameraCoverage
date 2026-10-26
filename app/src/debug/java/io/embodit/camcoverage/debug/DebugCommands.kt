package io.embodit.camcoverage.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.embodit.camcoverage.domain.Aim
import io.embodit.camcoverage.domain.CamType
import io.embodit.camcoverage.domain.Camera
import io.embodit.camcoverage.domain.LanguagePref
import io.embodit.camcoverage.domain.Layout
import io.embodit.camcoverage.domain.LengthUnit
import io.embodit.camcoverage.domain.Lens
import io.embodit.camcoverage.domain.Mode
import io.embodit.camcoverage.domain.Mount
import io.embodit.camcoverage.domain.Placement
import io.embodit.camcoverage.domain.PlaneSurface
import io.embodit.camcoverage.domain.Resolution
import io.embodit.camcoverage.domain.SurfaceHit
import io.embodit.camcoverage.domain.Vec3
import io.embodit.camcoverage.domain.Zone
import io.embodit.camcoverage.domain.ZoneHeight
import io.embodit.camcoverage.domain.ZoneSize
import io.embodit.camcoverage.platform.CcGraph
import io.embodit.camcoverage.platform.CcLog
import io.embodit.camcoverage.ui.cover.CoverEvent
import io.embodit.camcoverage.ui.cover.CoverViewModel
import io.embodit.camcoverage.ui.cover.Phase
import io.embodit.camcoverage.ui.cover.RestoreState
import io.embodit.camcoverage.ui.cover.SnackKind
import io.embodit.camcoverage.ui.cover.debug.Probe
import io.embodit.camcoverage.ui.cover.scene.CoverScene
import java.io.File
import java.util.Locale

/**
 * Test hooks of the debug build (design `qaHooks`). One `CMD` log line (tag `CamCoverage`) answers
 * every command, and every state change logs `STATE Sxx ...` with every camera.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.camcoverage.DEBUG -p io.embodit.camcoverage --es cmd NAME [--es s TEXT] [--es v TEXT] [--es mount wall|ceiling|tripod] [--es plane LABEL] [--ei lens N] [--ei res N] [--ei type N] [--ei size N] [--ei h N] [--ei id N] [--ef x N --ef y N --ef z N] [--ef yaw N --ef pitch N]"
 *
 * Commands: `state` s=S01..S24 (jump) | s=lost|blocked|saving_hold|anchor_fail|restoring|elsewhere|release (force) ·
 * `mode` v=work|home · `place` mount=wall|ceiling|tripod [x y z] or plane=LABEL · `aim` [id] yaw pitch ·
 * `lens` lens=0-4 res=0-2 | type=0-3 · `zone` size=0-2 h=0-2 [x y z] or plane=LABEL · `head` x y z | v=off ·
 * `occluders` · `coach_reset` · `seed` · `select` id · `tap` · `stick` a=x,ms · `ui` s=control · `lang` s · `unit` s ·
 * `snack` s=long|normal · `clear` · `dump` · `pose` · `panels` · `planes` · `bounds` · `hidemode` s=auto|park
 */
internal object DebugCommands {
    private val main = Handler(Looper.getMainLooper())

    fun run(context: Context, cmd: String, t: Map<String, String>, i: Map<String, Int>, f: Map<String, Float>) {
        val host = Probe.host
        if (host == null) {
            CcLog.w("CMD ignored: no Stage")
            return
        }
        val vm = host.viewModel()
        val scene = host.scene()
        val s = t["s"]
        val v = t["v"]
        val point = if (f.containsKey("x") && f.containsKey("y") && f.containsKey("z")) Vec3(f["x"]!!, f["y"]!!, f["z"]!!) else null
        when (cmd) {
            "state" -> if (s.isNullOrBlank()) CcLog.i(vm.stateLine()) else state(vm, scene, s, t, i, f)
            "mode" -> vm.onEvent(CoverEvent.ModeSelected(Mode.byTag(v ?: s)))
            "place" -> CcLog.i("CMD ${place(vm, scene, t["mount"] ?: s, t["plane"], point)}")
            "aim" -> {
                val id = i["id"] ?: vm.state.value.selected ?: vm.state.value.cameras.firstOrNull()?.id ?: return
                CcLog.i("CMD ${scene.debugAim(id, f["yaw"], f["pitch"])}")
            }
            "lens" -> {
                val id = i["id"] ?: vm.state.value.selected ?: vm.state.value.cameras.firstOrNull()?.id ?: return
                vm.onEvent(CoverEvent.CameraTapped(id))
                i["lens"]?.let { vm.onEvent(CoverEvent.LensSelected(Lens.byIndex(it))) }
                i["res"]?.let { vm.onEvent(CoverEvent.ResSelected(Resolution.byIndex(it))) }
                i["type"]?.let { vm.onEvent(CoverEvent.TypeSelected(CamType.byIndex(it))) }
            }
            "zone" -> CcLog.i("CMD ${zone(vm, scene, i["size"], i["h"], t["plane"], point)}")
            "head" -> {
                if (v == "off" || s == "off") CcGraph.head.debugHead = null else if (point != null) CcGraph.head.debugHead = point
                CcLog.i("CMD head=${CcLog.pos(CcGraph.head.debugHead)}")
            }
            "occluders" -> {
                scene.debugOccluders(true)
                later(4000) { scene.debugOccluders(false) }
            }
            "coach_reset" -> vm.debugCoachReset()
            "seed" -> CcLog.i("CMD ${seed(vm, scene, Mode.byTag(v ?: s ?: vm.state.value.mode.tag))}")
            "select" -> (i["id"] ?: s?.toIntOrNull())?.let { vm.onEvent(CoverEvent.CameraTapped(it)) }
            "zone_select" -> vm.onEvent(CoverEvent.ZoneTapped)
            "tap" -> vm.onEvent(CoverEvent.EmptyTapped)
            "stick" -> {
                val nums = (t["a"] ?: "").split(',').mapNotNull { it.trim().toFloatOrNull() }
                CcLog.i("CMD ${scene.debugStick(nums.getOrNull(0) ?: f["x"] ?: 0f, (nums.getOrNull(1) ?: 1000f).toLong())}")
            }
            "ui" -> ui(vm, scene, s ?: "")
            "lang" -> LanguagePref.entries.firstOrNull { it.tag == (v ?: s) }?.let { vm.onEvent(CoverEvent.LanguageSelected(it)) }
            "unit" -> LengthUnit.entries.firstOrNull { it.tag == (v ?: s) }?.let { vm.onEvent(CoverEvent.UnitSelected(it)) }
            "snack" -> vm.longSnacks = (v ?: s) == "long"
            "clear" -> {
                vm.debugSet { it.copy(clearDialog = true, clearBoth = (v ?: s) == "both") }
                vm.onEvent(CoverEvent.ClearConfirmed)
            }
            "dump" -> {
                val line = vm.stateLine()
                runCatching { File(context.filesDir, "dump.txt").writeText(line) }
                CcLog.i("DUMP $line")
            }
            "hidemode" -> CcLog.i("CMD ${scene.debugHideMode(s ?: "auto")}")
            "pose" -> CcLog.i(scene.poseLine())
            "panels" -> scene.panelLines().forEach { CcLog.i(it) }
            "planes" -> scene.planeLines().forEach { CcLog.i(it) }
            "bounds" ->
                listOf("console", "cameraCard", "zoneCard", "menu", "dialog", "snack", "coach", "tag0").forEach { panel ->
                    Probe.controlsOf(panel).forEach { (name, b) -> CcLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, name, b.cx, b.cy, b.w, b.h)) }
                }
            else -> CcLog.w("CMD unknown '$cmd'")
        }
    }

    private fun ui(vm: CoverViewModel, scene: CoverScene, name: String) {
        val e: CoverEvent? =
            when {
                name.startsWith("mode_") -> CoverEvent.ModeSelected(Mode.byTag(name.removePrefix("mode_")))
                name == "more" -> CoverEvent.MoreClicked
                name == "add_camera" -> CoverEvent.AddCameraClicked
                name == "undo" -> CoverEvent.UndoClicked
                name == "delete" -> CoverEvent.DeleteCamera
                name.startsWith("lens_") -> CoverEvent.LensSelected(Lens.byTag(name.removePrefix("lens_").replace('_', '.')))
                name.startsWith("res_") -> CoverEvent.ResSelected(Resolution.byTag(name.removePrefix("res_")))
                name.startsWith("type_") -> CoverEvent.TypeSelected(CamType.byTag(name.removePrefix("type_")))
                name == "aim_me" -> scene.headForAim()?.let { CoverEvent.AimAtMe(it) }
                name == "aim_zone" -> CoverEvent.AimAtZone
                name == "height_minus" -> CoverEvent.HeightStepped(-1)
                name == "height_plus" -> CoverEvent.HeightStepped(1)
                name.startsWith("zone_size_") -> CoverEvent.ZoneSizeSelected(ZoneSize.byTag(name.removePrefix("zone_size_")))
                name.startsWith("zone_h_") -> CoverEvent.ZoneHeightSelected(ZoneHeight.byTag(name.removePrefix("zone_h_")))
                name == "zone_rotate" -> CoverEvent.ZoneRotate
                name.startsWith("lang_") -> LanguagePref.entries.firstOrNull { it.tag == name.removePrefix("lang_") }?.let { CoverEvent.LanguageSelected(it) }
                name.startsWith("unit_") -> LengthUnit.entries.firstOrNull { it.tag == name.removePrefix("unit_") }?.let { CoverEvent.UnitSelected(it) }
                name == "menu_clear" -> CoverEvent.ClearAllClicked
                name == "menu_exit" -> CoverEvent.ExitClicked
                name == "dialog_cancel" -> CoverEvent.DialogDismissed
                name == "dialog_confirm" -> CoverEvent.ClearConfirmed
                name == "blocked_exit" -> CoverEvent.BlockedExit
                name == "blocked_retry" -> CoverEvent.BlockedRetry
                name == "snackbar_action" -> CoverEvent.SnackActionClicked
                name == "coach_ok" -> CoverEvent.CoachDismissed
                else -> null
            }
        if (e == null) CcLog.w("CMD ui unknown '$name'") else vm.onEvent(e)
    }

    private fun later(ms: Long, block: () -> Unit) = main.postDelayed(block, ms)

    // ---- placement helpers -----------------------------------------------------------------

    private fun hitFor(scene: CoverScene, vm: CoverViewModel, mount: String?, planeLabel: String?, point: Vec3?): SurfaceHit? {
        val room = vm.state.value.room
        val plane: PlaneSurface? =
            when {
                planeLabel != null -> scene.planeByLabel(planeLabel)
                point != null -> room.planes.minByOrNull { kotlin.math.abs((point - it.centroid).dot(it.normal)) + (if (it.contains(point, -0.05f)) 0f else 2f) }
                mount == "ceiling" -> room.ceilings.maxByOrNull { it.area }
                mount == "tripod" -> room.tables.maxByOrNull { it.area } ?: room.floors.maxByOrNull { it.area }
                mount == "floor" -> room.floors.maxByOrNull { it.area }
                else -> room.walls.maxByOrNull { it.area }
            }
        if (plane == null) return null
        return scene.surfaceHitAt(plane, point)
    }

    /** `place`: a camera on the given mount's biggest plane (or the plane / point given), like a tap in S07. */
    private fun place(vm: CoverViewModel, scene: CoverScene, mount: String?, planeLabel: String?, point: Vec3?): String {
        val hit = hitFor(scene, vm, mount, planeLabel, point) ?: return "place: no plane for mount=$mount plane=$planeLabel"
        val s = vm.state.value
        if (!s.placing) vm.onEvent(CoverEvent.AddCameraClicked)
        vm.onEvent(CoverEvent.PlaneTapped(hit))
        return "place on ${hit.plane.label} at ${CcLog.pos(hit.point)} -> ${vm.state.value.designState}"
    }

    /** `zone`: the work area on the given plane (default: the biggest table), M / 30 cm unless told. */
    private fun zone(vm: CoverViewModel, scene: CoverScene, size: Int?, h: Int?, planeLabel: String?, point: Vec3?): String {
        val room = vm.state.value.room
        val plane = (if (planeLabel != null) scene.planeByLabel(planeLabel) else null) ?: (if (point != null) room.tables.minByOrNull { kotlin.math.abs(it.centroid.y - point.y) } else null) ?: room.tables.maxByOrNull { it.area } ?: room.floors.maxByOrNull { it.area } ?: return "zone: no horizontal plane"
        val hit = scene.surfaceHitAt(plane, point)
        var z = Placement.zoneFor(hit) ?: return "zone: not horizontal"
        z = z.copy(size = ZoneSize.byIndex(size ?: 1), height = ZoneHeight.byIndex(h ?: 1))
        z = z.copy(center = Placement.fitInside(z, plane))
        vm.onEvent(CoverEvent.ModeSelected(Mode.WORKCELL))
        val zz = z
        vm.debugPut(Mode.WORKCELL) { it.copy(zone = zz) }
        vm.debugSet { it.copy(zoneSelected = true, selected = null, placing = false) }
        return "zone on ${plane.label} at ${CcLog.pos(z.center)}"
    }

    /** `seed`: an example layout for QA: a work area on the biggest table and three cameras (wall, ceiling, desk tripod) aimed at it; home: two wall cameras + a dome. */
    private fun seed(vm: CoverViewModel, scene: CoverScene, mode: Mode): String {
        val room = vm.state.value.room
        vm.onEvent(CoverEvent.ModeSelected(mode))
        vm.debugCancelCoach()
        val cams = ArrayList<Camera>()
        var zone: Zone? = null
        if (mode == Mode.WORKCELL) {
            val head = scene.headForAim() ?: Vec3(0f, 1.6f, 0f)
            val table = room.tables.filter { it.semantic == "TABLE" && it.area > 0.3f }.minByOrNull { it.centroid.distanceTo(head) } ?: room.tables.maxByOrNull { it.area } ?: room.floors.maxByOrNull { it.area }
            if (table != null) {
                val h = scene.surfaceHitAt(table, null)
                zone = Placement.zoneFor(h)?.let { it.copy(center = Placement.fitInside(it, table)) }
            }
            val target = zone?.boxCenter ?: Vec3(0f, 1f, -1.5f)
            // the wall nearest to the work area (within the 3 m range), else the biggest
            room.walls.filter { it.area > 1.5f }.minByOrNull { kotlin.math.abs((target - it.centroid).dot(it.normal)) }?.let { w ->
                val foot = w.project(target)
                val p = w.clampInside(Vec3(foot.x, (room.floorY ?: 0f) + 1.9f, foot.z), 0.05f)
                var n = w.normal
                if (n.dot(target - p) < 0f) n = -n
                Placement.cameraFor(1, SurfaceHit(p, n, 1f, w), room, mode, zone)?.let { cams += Aim.lookAt(it.copy(type = CamType.STANDARD), target) }
            }
            // the ceiling plane right above the work area (the lowest one there), else the biggest
            (room.ceilings.filter { it.contains(Vec3(target.x, it.centroid.y, target.z)) }.minByOrNull { it.centroid.y } ?: room.ceilings.maxByOrNull { it.area })?.let { c ->
                val p = c.clampInside(Vec3(target.x, c.centroid.y, target.z), 0.05f)
                Placement.cameraFor(2, SurfaceHit(p, Vec3(0f, -1f, 0f), 1f, c), room, mode, zone)?.let { cams += Aim.lookAt(it.copy(type = CamType.STANDARD), target) }
            }
            val z = zone
            if (z != null) {
                if (table != null) {
                    val p = table.clampInside(z.world(z.halfW + 0.25f, 0f, table.centroid.y), 0.05f)
                    Placement.cameraFor(3, SurfaceHit(p, Vec3.UP, 1f, table), room, mode, z)?.let { cams += Aim.lookAt(it.copy(type = CamType.DEPTH), target) }
                }
            }
        } else {
            val head = scene.headForAim() ?: Vec3(0f, 1.6f, 0f)
            val walls = room.walls.sortedByDescending { it.area }.take(2)
            for ((k, w) in walls.withIndex()) {
                val p = w.clampInside(Vec3(w.centroid.x, (room.floorY ?: 0f) + 2.4f, w.centroid.z), 0.05f)
                var n = w.normal
                if (n.dot(head - p) < 0f) n = -n
                Placement.cameraFor(k + 1, SurfaceHit(p, n, 1f, w), room, mode, null)?.let { cams += Aim.lookAt(it.copy(lens = if (k == 0) Lens.L28 else Lens.L4, res = Resolution.MP4), head) }
            }
            (room.ceilings.filter { it.contains(Vec3(head.x + 1f, it.centroid.y, head.z)) }.minByOrNull { it.centroid.y } ?: room.ceilings.maxByOrNull { it.area })?.let { c ->
                val p = c.clampInside(Vec3(head.x + 1f, c.centroid.y, head.z), 0.05f)
                Placement.cameraFor(cams.size + 1, SurfaceHit(p, Vec3(0f, -1f, 0f), 1f, c), room, mode, null)?.let { cams += Aim.lookAt(it, Vec3(head.x, head.y - 0.6f, head.z - 1.5f)) }
            }
        }
        val list = cams.toList()
        val zz = zone
        vm.debugPut(mode) { Layout(cameras = list, zone = zz) }
        vm.debugSet { it.copy(selected = null, zoneSelected = false, placing = false, menuOpen = false) }
        return "seed mode=${mode.tag} cameras=${cams.size} zone=${zone != null}"
    }

    /** A calm base: ready, nothing selected, no overlays; seeded when the mode has nothing yet. */
    private fun base(vm: CoverViewModel, scene: CoverScene, mode: Mode, seedIfEmpty: Boolean = true) {
        CcGraph.head.forceLost = false
        CcGraph.head.debugHead = null
        CcGraph.anchors.failNext = false
        CcGraph.anchors.holdMs = 0L
        vm.debugHoldScanning = false
        vm.debugHoldSaving = false
        vm.longSnacks = true
        scene.debugDragCancel()
        if (vm.state.value.phase == Phase.STARTING) vm.debugLeaveStarting()
        vm.debugRelease()
        vm.debugSet { it.copy(holdStarting = false, phase = if (it.phase == Phase.SCANNING && it.room.hasAnyMount) Phase.READY else it.phase, menuOpen = false, clearDialog = false, blocked = false, selected = null, zoneSelected = false, placing = false, aiming = null, dragging = null, coach = false, snack = null, trackingLost = false) }
        vm.debugCancelCoach()
        if (vm.state.value.mode != mode) vm.onEvent(CoverEvent.ModeSelected(mode))
        val l = vm.state.value.layouts[mode]
        if (seedIfEmpty && (l == null || l.cameras.isEmpty())) seed(vm, scene, mode)
    }

    private fun state(vm: CoverViewModel, scene: CoverScene, id: String, t: Map<String, String>, i: Map<String, Int>, f: Map<String, Float>) {
        when (id) {
            // forced conditions
            "lost" -> CcGraph.head.forceLost = true
            "blocked" -> vm.debugSet { it.copy(blocked = true, phase = Phase.UNAVAILABLE, menuOpen = false, clearDialog = false) }
            "saving_hold" -> {
                vm.debugHoldSaving = true
                vm.debugSet { it.copy(saving = true) }
            }
            "anchor_fail" -> {
                CcGraph.anchors.failNext = true
                vm.debugSaveFail()
            }
            "restoring" -> vm.debugRestoring()
            "elsewhere" -> vm.debugRestoreTimeout()
            "release" -> {
                CcGraph.head.forceLost = false
                vm.debugHoldSaving = false
                vm.debugRelease()
            }
            // designed states
            "S01" -> vm.debugSet { it.copy(holdStarting = true, phase = Phase.STARTING, snack = null, menuOpen = false, clearDialog = false) }
            "S02" -> {
                CcGraph.head.forceLost = false
                vm.debugHoldScanning = true
                vm.debugSet { it.copy(holdStarting = false, menuOpen = false, clearDialog = false, blocked = false, trackingLost = false, coach = false, selected = null, placing = false) }
                vm.debugLeaveStarting()
                vm.debugSet { it.copy(phase = Phase.SCANNING) }
                vm.debugSnack(SnackKind.SCANNING)
            }
            "S03" -> {
                base(vm, scene, Mode.WORKCELL, seedIfEmpty = false)
                vm.debugRestoring()
            }
            "S04" -> {
                base(vm, scene, Mode.WORKCELL, seedIfEmpty = false)
                vm.debugPut(Mode.WORKCELL) { it.copy(zone = null) }
            }
            "S05" -> {
                base(vm, scene, Mode.WORKCELL)
                vm.onEvent(CoverEvent.ZoneTapped)
            }
            "S06" -> {
                base(vm, scene, Mode.WORKCELL)
                vm.debugPut(Mode.WORKCELL) { it.copy(cameras = emptyList()) }
            }
            "S07" -> {
                base(vm, scene, Mode.WORKCELL)
                vm.onEvent(CoverEvent.AddCameraClicked)
            }
            "S08" -> {
                base(vm, scene, Mode.WORKCELL)
                later(300) { vm.state.value.cameras.firstOrNull()?.let { vm.onEvent(CoverEvent.CameraTapped(it.id)) } }
            }
            "S09" -> {
                base(vm, scene, Mode.WORKCELL)
                later(300) {
                    val c = vm.state.value.cameras.firstOrNull() ?: return@later
                    vm.onEvent(CoverEvent.CameraTapped(c.id))
                    vm.onEvent(CoverEvent.AimStarted(c.id))
                }
            }
            "S10" -> base(vm, scene, Mode.WORKCELL)
            "S11" -> {
                base(vm, scene, Mode.WORKCELL)
                later(400) {
                    // the viewer stands at the near edge of the work area, between the wall camera and the table
                    val s = vm.state.value
                    val z = s.zone ?: return@later
                    val cam = s.cameras.firstOrNull { it.mount == Mount.WALL } ?: s.cameras.firstOrNull() ?: return@later
                    // stand where the camera's ray to the work area's centre passes at chest height (1.5 m): the torso cuts it
                    val floor = s.room.floorY ?: 0f
                    val chest = floor + 1.5f
                    val t = ((cam.position.y - chest) / maxOf(0.2f, cam.position.y - z.boxCenter.y)).coerceIn(0.15f, 0.85f)
                    val onRay = cam.position + (z.boxCenter - cam.position) * t
                    val toZone = (z.boxCenter - onRay).let { Vec3(it.x, 0f, it.z) }.normalized().let { if (it.length < 0.5f) Vec3.AHEAD else it }
                    CcGraph.head.debugHead = Vec3(onRay.x, floor + 1.65f, onRay.z)
                    vm.debugBody(CcGraph.head.debugHead, toZone)
                    CcLog.i("CMD S11 head=${CcLog.pos(CcGraph.head.debugHead)}")
                }
            }
            "S12" -> {
                base(vm, scene, Mode.HOME, seedIfEmpty = false)
                vm.debugPut(Mode.HOME) { it.copy(cameras = emptyList()) }
            }
            "S13" -> {
                base(vm, scene, Mode.HOME)
                later(400) {
                    val c = vm.state.value.cameras.firstOrNull() ?: return@later
                    CcGraph.head.debugHead = c.position + c.dir * 2.5f
                    CcLog.i("CMD S13 head=${CcLog.pos(CcGraph.head.debugHead)}")
                }
            }
            "S14" -> {
                base(vm, scene, Mode.HOME)
                later(400) {
                    val c = vm.state.value.cameras.firstOrNull() ?: return@later
                    // behind the camera's own wall mount: never in its field
                    CcGraph.head.debugHead = c.position + c.dir * -0.5f + Vec3(0f, -0.3f, 0f)
                    CcLog.i("CMD S14 head=${CcLog.pos(CcGraph.head.debugHead)}")
                }
            }
            "S15" -> {
                base(vm, scene, Mode.WORKCELL)
                later(300) { vm.onEvent(CoverEvent.MoreClicked) }
            }
            "S16" -> {
                base(vm, scene, Mode.WORKCELL)
                later(300) { vm.debugSet { it.copy(clearDialog = true, clearBoth = false) } }
            }
            "S17" -> {
                base(vm, scene, Mode.WORKCELL)
                later(300) {
                    vm.debugSet { it.copy(restore = RestoreState.RESTORED) }
                    vm.debugSnack(SnackKind.RESTORED, vm.state.value.cameras.size)
                }
            }
            "S18" -> {
                base(vm, scene, Mode.WORKCELL, seedIfEmpty = false)
                later(300) { vm.debugRestoreTimeout() }
            }
            "S19" -> {
                base(vm, scene, Mode.WORKCELL)
                later(300) { vm.debugSaveFail() }
            }
            "S20" -> {
                base(vm, scene, Mode.WORKCELL)
                later(300) {
                    val s = vm.state.value
                    val room = s.room
                    val target = s.zone?.boxCenter ?: Vec3(0f, 1f, -1.5f)
                    val extra = ArrayList<Camera>()
                    val walls = room.walls.sortedByDescending { it.area }
                    var k = s.cameras.size
                    var n = 0
                    while (k < Layout.MAX_CAMERAS && walls.isNotEmpty()) {
                        val w = walls[n % walls.size]
                        val off = (n / walls.size + 1) * 0.6f * (if (n % 2 == 0) 1f else -1f)
                        val right = Vec3.UP.cross(w.normal).normalized()
                        val p = w.clampInside(w.centroid + right * off + Vec3(0f, 0.5f, 0f), 0.05f)
                        var nn = w.normal
                        if (nn.dot(target - p) < 0f) nn = -nn
                        val id = (1..Layout.MAX_CAMERAS).first { cid -> s.cameras.none { it.id == cid } && extra.none { it.id == cid } }
                        Placement.cameraFor(id, SurfaceHit(p, nn, 1f, w), room, s.mode, s.zone)?.let { extra += Aim.lookAt(it, target) }
                        k++
                        n++
                    }
                    vm.debugPut(s.mode) { it.copy(cameras = it.cameras + extra) }
                    vm.debugSnack(SnackKind.LIMIT)
                }
            }
            "S21" -> {
                base(vm, scene, Mode.WORKCELL)
                later(300) { CcGraph.head.forceLost = true }
            }
            "S22" -> {
                CcGraph.head.forceLost = false
                vm.debugSet { it.copy(blocked = true, phase = Phase.UNAVAILABLE, menuOpen = false, clearDialog = false, trackingLost = false, snack = null, selected = null, coach = false) }
            }
            "S23" -> {
                base(vm, scene, Mode.WORKCELL)
                later(300) {
                    vm.debugHoldSaving = true
                    vm.debugSet { it.copy(saving = true) }
                }
            }
            "S24" -> {
                base(vm, scene, Mode.WORKCELL)
                vm.debugCoachReset()
                later(300) { vm.debugSet { it.copy(coach = true) } }
            }
            else -> CcLog.w("CMD state unknown '$id'")
        }
        CcLog.i("CMD state $id requested")
        later(1500) { CcLog.i(vm.stateLine()) }
    }
}

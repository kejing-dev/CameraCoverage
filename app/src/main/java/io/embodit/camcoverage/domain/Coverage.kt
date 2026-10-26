package io.embodit.camcoverage.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The detected room (design `entities3d.occluders`, `rules.zone`): which planes block rays, which take
 * a camera, which take the work area. Pure data over the plane list.
 */
data class RoomModel(val planes: List<PlaneSurface>) {
    val floors: List<PlaneSurface> = planes.filter { it.semantic == "FLOOR" && it.isHorizontal }
    val ceilings: List<PlaneSurface> = planes.filter { it.semantic == "CEILING" && it.isHorizontal }

    /** Vertical planes that take a wall camera and block rays: everything upright except doors and windows. */
    val walls: List<PlaneSurface> = planes.filter { it.isVertical && it.semantic !in TRANSPARENT }

    /** Horizontal planes other than the floor: tables, cabinets, beds... (a desk pole or the work area). */
    val tables: List<PlaneSurface> = planes.filter { it.isHorizontal && it.semantic != "FLOOR" && it.semantic != "CEILING" && it.normal.y > 0f || (it.isHorizontal && it.semantic != "FLOOR" && it.semantic != "CEILING" && it.centroid.y < 1.6f) }

    /** Planes that block a ray (design `assumptions`: doors and windows count as open). */
    val occluders: List<PlaneSurface> = planes.filter { it.semantic !in TRANSPARENT }

    val floorY: Float? = floors.minOfOrNull { it.centroid.y }

    val hasFloor: Boolean
        get() = floors.isNotEmpty()

    val hasAnyMount: Boolean
        get() = planes.isNotEmpty()

    fun plane(id: String): PlaneSurface? = planes.firstOrNull { it.id == id }

    /** The floor plane under [p] (or the nearest one). */
    fun floorUnder(p: Vec3): PlaneSurface? = floors.firstOrNull { it.contains(p) } ?: floors.minByOrNull { it.centroid.distanceTo(p) }

    /** Horizontal centre of everything detected (the mini room's origin). */
    val center: Vec3 by lazy {
        if (planes.isEmpty()) Vec3.ZERO
        else {
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (p in planes) for (v in p.vertices) {
                minX = min(minX, v.x); maxX = max(maxX, v.x); minY = min(minY, v.y); maxY = max(maxY, v.y); minZ = min(minZ, v.z); maxZ = max(maxZ, v.z)
            }
            Vec3((minX + maxX) / 2f, minY, (minZ + maxZ) / 2f)
        }
    }

    /** Longest horizontal extent (for the mini room's scale check). */
    val extent: Float by lazy {
        if (planes.isEmpty()) 0f
        else {
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (p in planes) for (v in p.vertices) {
                minX = min(minX, v.x); maxX = max(maxX, v.x); minZ = min(minZ, v.z); maxZ = max(maxZ, v.z)
            }
            max(maxX - minX, maxZ - minZ)
        }
    }

    /** First occluder hit along the segment [from] -> [to] (strictly between, [skipNear] metres from the start ignored). */
    fun blocks(from: Vec3, to: Vec3, skipNear: Float = 0.02f): Boolean {
        val d = to - from
        val len = d.length
        if (len < 1e-4f) return false
        val dir = d / len
        for (p in occluders) {
            val h = p.hit(from, dir, len - 0.01f) ?: continue
            if (h.distance > skipNear) return true
        }
        return false
    }

    /** Nearest occluder hit of a ray, or null. */
    fun firstHit(from: Vec3, dir: Vec3, maxM: Float, skipNear: Float = 0.02f): SurfaceHit? {
        var best: SurfaceHit? = null
        for (p in occluders) {
            val h = p.hit(from, dir, maxM) ?: continue
            if (h.distance <= skipNear) continue
            if (best == null || h.distance < best.distance) best = h
        }
        return best
    }

    companion object {
        val EMPTY = RoomModel(emptyList())
        val TRANSPARENT = setOf("DOOR", "WINDOW")
    }
}

/** The person as the occluder (design `entities3d.bodyProxy`): head sphere + torso capsule. */
data class BodyProxy(val head: Vec3, val forward: Vec3) {
    private val back: Vec3 = Vec3(-forward.x, 0f, -forward.z).normalized().let { if (it.length < 0.5f) Vec3(0f, 0f, 1f) else it } * 0.05f
    val capsuleTop: Vec3 = head + back - Vec3(0f, 0.20f, 0f)
    val capsuleBottom: Vec3 = head + back - Vec3(0f, 1.40f, 0f)

    /** True when the segment [a] -> [b] passes through the head sphere or the torso capsule. */
    fun blocks(a: Vec3, b: Vec3): Boolean = segmentSphere(a, b, head, HEAD_R) || segmentCapsule(a, b, capsuleTop, capsuleBottom, TORSO_R)

    companion object {
        const val HEAD_R = 0.12f
        const val TORSO_R = 0.20f

        fun segmentSphere(a: Vec3, b: Vec3, c: Vec3, r: Float): Boolean {
            val ab = b - a
            val len2 = ab.dot(ab)
            if (len2 < 1e-9f) return a.distanceTo(c) <= r
            val t = ((c - a).dot(ab) / len2).coerceIn(0f, 1f)
            return (a + ab * t).distanceTo(c) <= r
        }

        /** Segment / capsule: the closest distance between the two segments against the radius. */
        fun segmentCapsule(a: Vec3, b: Vec3, p: Vec3, q: Vec3, r: Float): Boolean = segmentDistance(a, b, p, q) <= r

        private fun segmentDistance(p1: Vec3, q1: Vec3, p2: Vec3, q2: Vec3): Float {
            val d1 = q1 - p1
            val d2 = q2 - p2
            val rr = p1 - p2
            val a = d1.dot(d1)
            val e = d2.dot(d2)
            val f = d2.dot(rr)
            var s: Float
            var t: Float
            if (a <= 1e-9f && e <= 1e-9f) return p1.distanceTo(p2)
            if (a <= 1e-9f) {
                s = 0f
                t = (f / e).coerceIn(0f, 1f)
            } else {
                val c = d1.dot(rr)
                if (e <= 1e-9f) {
                    t = 0f
                    s = (-c / a).coerceIn(0f, 1f)
                } else {
                    val b = d1.dot(d2)
                    val denom = a * e - b * b
                    s = if (denom != 0f) ((b * f - c * e) / denom).coerceIn(0f, 1f) else 0f
                    t = (b * s + f) / e
                    if (t < 0f) {
                        t = 0f
                        s = (-c / a).coerceIn(0f, 1f)
                    } else if (t > 1f) {
                        t = 1f
                        s = ((b - c) / a).coerceIn(0f, 1f)
                    }
                }
            }
            return (p1 + d1 * s).distanceTo(p2 + d2 * t)
        }
    }
}

/** A point's view from a camera: inside the field (with a margin in degrees) and its depth along the axis. */
object Frustum {
    /** Depth along the axis when [p] is inside the field grown by [marginDeg] (negative shrinks), else null. */
    fun depth(camera: Camera, mode: Mode, p: Vec3, marginDeg: Float = 0f): Float? {
        val (f, r, u) = camera.basis()
        val d = p - camera.position
        val z = d.dot(f)
        if (z <= 1e-3f || z > Optics.maxRange(mode)) return null
        val x = d.dot(r)
        val y = d.dot(u)
        val th = tan(Math.toRadians((camera.hfov(mode) / 2f + marginDeg).toDouble().coerceIn(1.0, 89.0))).toFloat()
        val tv = tan(Math.toRadians((camera.vfov(mode) / 2f + marginDeg).toDouble().coerceIn(1.0, 89.0))).toFloat()
        if (abs(x) > z * th || abs(y) > z * tv) return null
        return z
    }
}

/**
 * The cone's rays (design `entities3d.coneVolume`, `footprint`): a 9 x 6 grid over the field of view,
 * each ray stopped by the first occluder (or the mode's range). [hits] is row-major, row 0 = bottom.
 */
data class ConeGeom(val origin: Vec3, val hits: List<Vec3>, val planeIds: List<String?>, val normals: List<Vec3?>, val maxRange: Float) {
    fun at(i: Int, j: Int): Vec3 = hits[j * ConeRays.COLS + i]

    fun planeAt(i: Int, j: Int): String? = planeIds[j * ConeRays.COLS + i]

    fun normalAt(i: Int, j: Int): Vec3? = normals[j * ConeRays.COLS + i]

    /** The four corners (bottom-left, bottom-right, top-right, top-left). */
    val corners: List<Vec3>
        get() = listOf(at(0, 0), at(ConeRays.COLS - 1, 0), at(ConeRays.COLS - 1, ConeRays.ROWS - 1), at(0, ConeRays.ROWS - 1))
}

object ConeRays {
    const val COLS = 9
    const val ROWS = 6

    fun cast(camera: Camera, mode: Mode, room: RoomModel): ConeGeom {
        val (f, r, u) = camera.basis()
        val th = tan(Math.toRadians(camera.hfov(mode) / 2.0)).toFloat()
        val tv = tan(Math.toRadians(camera.vfov(mode) / 2.0)).toFloat()
        val range = Optics.maxRange(mode)
        val hits = ArrayList<Vec3>(COLS * ROWS)
        val ids = ArrayList<String?>(COLS * ROWS)
        val normals = ArrayList<Vec3?>(COLS * ROWS)
        val o = camera.position
        for (j in 0 until ROWS) {
            val ay = (j / (ROWS - 1f) - 0.5f) * 2f * tv
            for (i in 0 until COLS) {
                val ax = (i / (COLS - 1f) - 0.5f) * 2f * th
                val dir = (f + r * ax + u * ay).normalized()
                val h = room.firstHit(o, dir, range, skipNear = 0.03f)
                if (h != null) {
                    hits += h.point
                    ids += h.plane.id
                    normals += h.normal
                } else {
                    hits += o + dir * range
                    ids += null
                    normals += null
                }
            }
        }
        return ConeGeom(o, hits, ids, normals, range)
    }
}

/** One cell of a coverage grid. [center] is the sample point's foot (on the surface); [band] the grade or count. */
data class Cell(val center: Vec3, val right: Vec3, val up: Vec3, val halfW: Float, val halfH: Float, val normal: Vec3, val band: Int, val downgraded: Boolean = false)

/** Per-camera numbers for the tags and the card (design `entities3d.cameraTagReadout`). */
data class CameraStat(
    val id: Int,
    /** Home: px/m at the viewer's head when seen, else null. Workcell: px/cm at the work area's centre. */
    val value: Float?,
    /** Workcell depth camera: distance to the work area's centre. */
    val depthM: Float?,
    val inDepthRange: Boolean,
    /** Share of the work area's samples this camera loses to the body (0..1). */
    val blockedShare: Float,
    val seesZone: Boolean,
)

/** The result of one coverage pass (design `rules.coverage`). */
data class CoverageResult(
    val mode: Mode,
    /** Home: floor cells, band = Dori ordinal. */
    val floor: List<Cell> = emptyList(),
    /** Home: wall cells, band = Dori ordinal. */
    val wall: List<Cell> = emptyList(),
    /** Workcell: zone cells, band = min(count, 3). */
    val zone: List<Cell> = emptyList(),
    val stats: List<CameraStat> = emptyList(),
    /** Home: share of floor cells that are blind (0..1). */
    val blindShare: Float = 0f,
    /** Workcell: share of zone cells seen by two or more cameras; number of cells seen by none. */
    val twoPlusShare: Float = 0f,
    val zeroCells: Int = 0,
    /** Workcell: cameras blocked by the body beyond the hysteresis (set by the engine, kept across passes). */
    val blockedCameras: Set<Int> = emptySet(),
    val withBody: Boolean = false,
) {
    fun stat(id: Int): CameraStat? = stats.firstOrNull { it.id == id }
}

/** The coverage maths, run on a background thread (design `rules.coverage`, `modes.*.metric`). */
object Coverage {
    const val HOME_CELL = 0.25f
    const val HOME_CELL_BIG = 0.5f
    const val HOME_SAMPLE_H = 1.6f
    const val HOME_MAX_CELLS = 1600
    const val WALL_TOP = 2.25f
    const val WALL_OFF = 0.03f
    const val ZONE_CELL = 0.05f
    const val BODY_NEAR_M = 1.5f
    const val BLOCK_ENTER = 0.20f
    const val BLOCK_EXIT = 0.12f

    fun compute(mode: Mode, layout: Layout, room: RoomModel, body: BodyProxy?, previousBlocked: Set<Int>): CoverageResult =
        if (mode == Mode.HOME) home(layout, room) else workcell(layout, room, body, previousBlocked)

    // ---- home -------------------------------------------------------------------------------

    private fun home(layout: Layout, room: RoomModel): CoverageResult {
        val cams = layout.cameras
        val floorCells = ArrayList<Cell>()
        var blind = 0
        val floors = room.floors
        if (floors.isNotEmpty()) {
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (p in floors) for (v in p.vertices) {
                minX = min(minX, v.x); maxX = max(maxX, v.x); minZ = min(minZ, v.z); maxZ = max(maxZ, v.z)
            }
            var cell = HOME_CELL
            if (((maxX - minX) / cell) * ((maxZ - minZ) / cell) > HOME_MAX_CELLS) cell = HOME_CELL_BIG
            val nx = ceil((maxX - minX) / cell).toInt().coerceAtLeast(1)
            val nz = ceil((maxZ - minZ) / cell).toInt().coerceAtLeast(1)
            val gap = 0.02f
            for (ix in 0 until nx) for (iz in 0 until nz) {
                val cx = minX + (ix + 0.5f) * cell
                val cz = minZ + (iz + 0.5f) * cell
                val floor = floors.firstOrNull { it.contains(Vec3(cx, it.centroid.y, cz)) } ?: continue
                val foot = Vec3(cx, floor.centroid.y, cz)
                val sample = foot + Vec3(0f, HOME_SAMPLE_H, 0f)
                val grade = bestGrade(cams, room, sample)
                if (grade == Dori.BLIND) blind++
                floorCells += Cell(foot, Vec3.RIGHT, Vec3.AHEAD, cell / 2f - gap / 2f, cell / 2f - gap / 2f, Vec3.UP, grade.ordinal)
            }
        }
        // walls: 25 cm cells from the floor to 2.25 m, 3 cm off the wall on the room's side
        val wallCells = ArrayList<Cell>()
        val roomCenter = room.center
        for (w in room.walls) {
            val n0 = w.normal
            val toRoom = roomCenter - w.centroid
            val n = if (n0.dot(toRoom) >= 0f) n0 else -n0
            val right = Vec3.UP.cross(n).normalized()
            if (right.length < 0.5f) continue
            var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (v in w.vertices) {
                val d = v - w.centroid
                u0 = min(u0, d.dot(right)); u1 = max(u1, d.dot(right)); y0 = min(y0, v.y); y1 = max(y1, v.y)
            }
            if (y1 - y0 < 0.8f || u1 - u0 < 0.3f) continue
            val base = room.floorY ?: y0
            val top = min(y1, base + WALL_TOP)
            val cols = ceil((u1 - u0) / HOME_CELL).toInt().coerceIn(1, 200)
            val rows = ceil((top - base) / HOME_CELL).toInt().coerceIn(1, 12)
            val cw = (u1 - u0) / cols
            val ch = (top - base) / rows
            for (c in 0 until cols) for (r in 0 until rows) {
                val center = w.centroid + right * (u0 + (c + 0.5f) * cw)
                val y = base + (r + 0.5f) * ch
                val onWall = Vec3(center.x, y, center.z)
                if (!w.contains(onWall)) continue
                val sample = onWall + n * WALL_OFF
                val grade = bestGrade(cams, room, sample)
                wallCells += Cell(onWall + n * 0.015f, right, Vec3.UP, cw / 2f - 0.01f, ch / 2f - 0.01f, n, grade.ordinal)
            }
        }
        val stats = cams.map { c -> CameraStat(c.id, null, null, true, 0f, false) }
        return CoverageResult(Mode.HOME, floor = floorCells, wall = wallCells, stats = stats, blindShare = if (floorCells.isEmpty()) 1f else blind / floorCells.size.toFloat())
    }

    /** The best DORI grade any camera reaches at [sample] (in field, unblocked). */
    fun bestGrade(cams: List<Camera>, room: RoomModel, sample: Vec3): Dori {
        var best = Dori.BLIND
        for (c in cams) {
            val z = Frustum.depth(c, Mode.HOME, sample) ?: continue
            if (room.blocks(c.position, sample)) continue
            val g = Optics.dori(Optics.pxPerM(c.px(Mode.HOME), c.hfov(Mode.HOME), z))
            if (g.ordinal < best.ordinal) best = g
        }
        return best
    }

    /** The viewer's own judgement (design `rules.you`): seen by which cameras, best px/m, with a field margin for hysteresis. */
    data class You(val seenBy: Set<Int>, val pxPerM: Float, val grade: Dori)

    fun you(cams: List<Camera>, room: RoomModel, head: Vec3, marginDeg: Float): You {
        val seen = HashSet<Int>()
        var best = 0f
        for (c in cams) {
            val z = Frustum.depth(c, Mode.HOME, head, marginDeg) ?: continue
            if (room.blocks(c.position, head)) continue
            seen += c.id
            best = max(best, Optics.pxPerM(c.px(Mode.HOME), c.hfov(Mode.HOME), z))
        }
        return You(seen, best, if (seen.isEmpty()) Dori.BLIND else Optics.dori(best))
    }

    // ---- workcell -----------------------------------------------------------------------------

    private fun workcell(layout: Layout, room: RoomModel, body: BodyProxy?, previousBlocked: Set<Int>): CoverageResult {
        val cams = layout.cameras
        val zone = layout.zone ?: return CoverageResult(Mode.WORKCELL, stats = cams.map { CameraStat(it.id, null, null, true, 0f, false) })
        val cols = (zone.size.w / ZONE_CELL).toInt().coerceAtLeast(1)
        val rows = (zone.size.d / ZONE_CELL).toInt().coerceAtLeast(1)
        val heights = floatArrayOf(0.01f, zone.height.m / 2f, zone.height.m - 0.01f)
        val cells = ArrayList<Cell>(cols * rows)
        var twoPlus = 0
        var zero = 0
        val visibleNoBody = IntArray(cams.size)
        val lostToBody = IntArray(cams.size)
        val along = zone.along.at(0f)
        val across = zone.across.at(0f)
        for (c in 0 until cols) for (r in 0 until rows) {
            val u = -zone.halfW + (c + 0.5f) * ZONE_CELL
            val v = -zone.halfD + (r + 0.5f) * ZONE_CELL
            var count = 0
            var countNoBody = 0
            for ((k, cam) in cams.withIndex()) {
                var all = true
                var allNoBody = true
                for (h in heights) {
                    val p = zone.world(u, v, zone.center.y + h)
                    val ok = sees(cam, room, p, null)
                    if (!ok) {
                        all = false
                        allNoBody = false
                        break
                    }
                    if (body != null && all && body.blocks(cam.position, p)) all = false
                }
                if (allNoBody) {
                    visibleNoBody[k]++
                    countNoBody++
                }
                if (all) count++ else if (allNoBody) lostToBody[k]++
            }
            if (count >= 2) twoPlus++
            if (count == 0) zero++
            cells += Cell(zone.world(u, v, zone.center.y + 0.003f), along, across, ZONE_CELL / 2f - 0.003f, ZONE_CELL / 2f - 0.003f, Vec3.UP, min(count, 3), downgraded = count < countNoBody)
        }
        val centre = zone.world(0f, 0f, zone.center.y + zone.height.m / 2f)
        val blocked = HashSet<Int>()
        val stats =
            cams.mapIndexed { k, cam ->
                val share = if (visibleNoBody[k] == 0) 0f else lostToBody[k] / visibleNoBody[k].toFloat()
                val was = cam.id in previousBlocked
                val now = if (was) share >= BLOCK_EXIT else share > BLOCK_ENTER
                if (now && body != null) blocked += cam.id
                val z = Frustum.depth(cam, Mode.WORKCELL, centre)
                val dist = cam.position.distanceTo(centre)
                val pxcm = if (z != null && !cam.isDepth) Optics.pxPerM(cam.px(Mode.WORKCELL), cam.hfov(Mode.WORKCELL), z) / 100f else null
                CameraStat(cam.id, pxcm, if (cam.isDepth) dist else null, if (cam.isDepth) dist in Optics.DEPTH_MIN_M..Optics.DEPTH_MAX_M else true, share, visibleNoBody[k] > 0)
            }
        return CoverageResult(Mode.WORKCELL, zone = cells, stats = stats, twoPlusShare = if (cells.isEmpty()) 0f else twoPlus / cells.size.toFloat(), zeroCells = zero, blockedCameras = blocked, withBody = body != null)
    }

    /** One sample for one camera in workcell terms: in field, not behind a plane, clear enough (or in depth range). */
    private fun sees(cam: Camera, room: RoomModel, p: Vec3, body: BodyProxy?): Boolean {
        val z = Frustum.depth(cam, Mode.WORKCELL, p) ?: return false
        if (cam.isDepth) {
            val d = cam.position.distanceTo(p)
            if (d < Optics.DEPTH_MIN_M || d > Optics.DEPTH_MAX_M) return false
        } else {
            val pxcm = Optics.pxPerM(cam.px(Mode.WORKCELL), cam.hfov(Mode.WORKCELL), z) / 100f
            if (pxcm < Optics.CLARITY_MIN_PX_CM) return false
        }
        if (room.blocks(cam.position, p)) return false
        if (body != null && body.blocks(cam.position, p)) return false
        return true
    }
}

/** Placement rules (design `rules.place`, `rules.zone`, `rules.moveCamera`). */
object Placement {
    const val EDGE_INSET = 0.05f

    /** The camera a tap on [hit] creates (mode decides the default type), or null when the plane cannot take one. */
    fun cameraFor(id: Int, hit: SurfaceHit, room: RoomModel, mode: Mode, zone: Zone?): Camera? {
        val plane = hit.plane
        val point = plane.clampInside(hit.point, EDGE_INSET)
        return when {
            plane.isVertical -> {
                val n = hit.normal.let { Vec3(it.x, 0f, it.z).normalized() }
                if (n.length < 0.5f) return null
                Camera(id, Mount.WALL, point, n, Aim.initialDir(Mount.WALL, n), surface = plane.semantic, type = if (mode == Mode.WORKCELL) CamType.STANDARD else CamType.STANDARD)
            }
            plane.isHorizontal && plane.semantic == "CEILING" -> Camera(id, Mount.CEILING, point, Vec3(0f, -1f, 0f), Vec3(0f, -1f, 0f), surface = plane.semantic)
            plane.isHorizontal -> {
                val onFloor = plane.semantic == "FLOOR"
                // the work area's own table top refuses a tripod inside its footprint (design R2-1)
                if (zone != null && !onFloor && abs(plane.centroid.y - zone.center.y) < 0.05f && zone.containsFlat(point)) return null
                Camera(id, Mount.TRIPOD, point, Vec3.UP, Vec3.AHEAD, height = if (onFloor) Camera.Dims.FLOOR_TRIPOD_DEFAULT else Camera.Dims.DESK_TRIPOD_DEFAULT, onFloor = onFloor, surface = plane.semantic)
            }
            else -> null
        }
    }

    /** True when the tap would land a tripod inside the work area's footprint on its own table (the "that's the work area" case). */
    fun insideZone(hit: SurfaceHit, zone: Zone?): Boolean {
        if (zone == null) return false
        val plane = hit.plane
        return plane.isHorizontal && plane.semantic != "CEILING" && plane.semantic != "FLOOR" && abs(plane.centroid.y - zone.center.y) < 0.05f && zone.containsFlat(hit.point)
    }

    /** The work area for a tap at [hit] on a horizontal plane: M, 30 cm, long edge along the plane's long edge, pushed inside. */
    fun zoneFor(hit: SurfaceHit): Zone? {
        val plane = hit.plane
        if (!plane.isHorizontal) return null
        // the plane's long edge
        var best = Vec3.RIGHT
        var bestLen = 0f
        val v = plane.vertices
        for (i in v.indices) {
            val e = v[(i + 1) % v.size] - v[i]
            val flat = Vec3(e.x, 0f, e.z)
            if (flat.length > bestLen) {
                bestLen = flat.length
                best = flat
            }
        }
        val along = P2(best.x, best.z).normalized()
        var zone = Zone(center = Vec3(hit.point.x, plane.centroid.y, hit.point.z), along = along)
        zone = zone.copy(center = fitInside(zone, plane))
        return zone
    }

    /** The zone centre pushed so that the footprint stays on the plane (edge inset 5 cm). */
    fun fitInside(zone: Zone, plane: PlaneSurface): Vec3 {
        var c = Vec3(zone.center.x, plane.centroid.y, zone.center.z)
        repeat(3) {
            for ((u, v) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val corner = zone.copy(center = c).world(u * zone.halfW, v * zone.halfD, c.y)
                val inside = plane.clampInside(corner, EDGE_INSET)
                val push = inside - corner
                c = c + Vec3(push.x, 0f, push.z)
            }
        }
        return c
    }

    /** Height step for a tripod (design T8). */
    fun stepHeight(c: Camera, delta: Int): Camera {
        if (c.mount != Mount.TRIPOD) return c
        val lo = if (c.onFloor) Camera.Dims.FLOOR_TRIPOD_MIN else Camera.Dims.DESK_TRIPOD_MIN
        val hi = if (c.onFloor) Camera.Dims.FLOOR_TRIPOD_MAX else Camera.Dims.DESK_TRIPOD_MAX
        val h = (Math.round((c.height + delta * Camera.Dims.TRIPOD_STEP) * 10f) / 10f).coerceIn(lo, hi)
        return c.copy(height = h)
    }

    /** A camera slid on its plane to [point] (clamped inside, 5 cm off the edges; a tripod stays out of the work area). */
    fun slide(c: Camera, plane: PlaneSurface, point: Vec3, zone: Zone?): Camera {
        var p = plane.clampInside(point, EDGE_INSET)
        if (c.mount == Mount.TRIPOD && zone != null && !c.onFloor && abs(plane.centroid.y - zone.center.y) < 0.05f && zone.containsFlat(p)) {
            // push out of the footprint along the shorter way
            val l = zone.local(p)
            val dx = zone.halfW + EDGE_INSET - abs(l.x)
            val dz = zone.halfD + EDGE_INSET - abs(l.z)
            p = if (dx < dz) zone.world(kotlin.math.sign(l.x).let { if (it == 0f) 1f else it } * (zone.halfW + EDGE_INSET), l.z, p.y) else zone.world(l.x, kotlin.math.sign(l.z).let { if (it == 0f) 1f else it } * (zone.halfD + EDGE_INSET), p.y)
        }
        return c.copy(mountPoint = p)
    }

    /** Where the view line from [origin] along [dir] meets the unbounded plane of [plane] (for drags). */
    fun onPlane(plane: PlaneSurface, origin: Vec3, dir: Vec3): Vec3? = plane.hitInfinite(origin, dir)?.point

    /** The aim knob under a pointing line: nearest point of the 0.6 m sphere about the lens (design `rules.aimDrag`). */
    fun knobTarget(lens: Vec3, origin: Vec3, dir: Vec3, radius: Float): Vec3 {
        val oc = origin - lens
        val b = oc.dot(dir)
        val c = oc.dot(oc) - radius * radius
        val disc = b * b - c
        if (disc >= 0f) {
            val t = -b - sqrt(disc)
            if (t > 0f) return origin + dir * t
        }
        // no hit: the closest point of the line to the centre, projected onto the sphere
        val t = max(0f, -b)
        val closest = origin + dir * t
        return lens + (closest - lens).normalized() * radius
    }

    fun floorTo(v: Float, step: Float): Float = floor(v / step) * step
}

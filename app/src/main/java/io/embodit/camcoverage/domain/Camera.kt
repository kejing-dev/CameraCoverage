package io.embodit.camcoverage.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** The two modes (design `modes`): each keeps its own layout. */
enum class Mode(val tag: String) {
    WORKCELL("work"),
    HOME("home");

    companion object {
        fun byTag(tag: String?): Mode = entries.firstOrNull { it.tag == tag } ?: WORKCELL
    }
}

/** How a camera is mounted (design `rules.place`). */
enum class Mount(val tag: String) {
    WALL("wall"),
    CEILING("ceiling"),
    TRIPOD("tripod");

    companion object {
        fun byTag(tag: String?): Mount = entries.firstOrNull { it.tag == tag } ?: WALL
    }
}

/** Work area sizes (cm, design `panels.zoneCard`). */
enum class ZoneSize(val w: Float, val d: Float, val tag: String) {
    S(0.40f, 0.30f, "S"),
    M(0.60f, 0.40f, "M"),
    L(0.90f, 0.60f, "L");

    companion object {
        fun byIndex(i: Int): ZoneSize = entries.getOrElse(i) { M }

        fun byTag(tag: String?): ZoneSize = entries.firstOrNull { it.tag == tag } ?: M
    }
}

enum class ZoneHeight(val m: Float, val tag: String) {
    H20(0.20f, "20"),
    H30(0.30f, "30"),
    H50(0.50f, "50");

    companion object {
        fun byIndex(i: Int): ZoneHeight = entries.getOrElse(i) { H30 }

        fun byTag(tag: String?): ZoneHeight = entries.firstOrNull { it.tag == tag } ?: H30
    }
}

/**
 * One virtual camera in Stage metres. [mountPoint] is where it sits on its surface, [normal] the
 * surface normal pointing into the room (ceiling: down, tripod base: up), [dir] the optical axis.
 * [height] is the tripod pole length (0 for the other mounts). [onFloor] tells a long tripod from a
 * short desk pole.
 */
data class Camera(
    val id: Int,
    val mount: Mount,
    val mountPoint: Vec3,
    val normal: Vec3,
    val dir: Vec3,
    val height: Float = 0f,
    val onFloor: Boolean = false,
    val lens: Lens = Lens.L4,
    val res: Resolution = Resolution.MP4,
    val type: CamType = CamType.STANDARD,
    /** The surface's semantic label, for the card ("wall", "table"...). */
    val surface: String = "",
) {
    /** The optical centre: 8 cm off a wall, 5 cm under the ceiling, on top of the tripod pole. */
    val position: Vec3
        get() =
            when (mount) {
                Mount.WALL -> mountPoint + normal * Dims.WALL_STANDOFF
                Mount.CEILING -> mountPoint + normal * Dims.DOME_DROP
                Mount.TRIPOD -> mountPoint + Vec3.UP * (height + Dims.TRIPOD_HEAD)
            }

    fun hfov(mode: Mode): Float = if (mode == Mode.HOME) lens.hfovDeg else type.hfovDeg

    fun vfov(mode: Mode): Float = if (mode == Mode.HOME) lens.vfovDeg else type.vfovDeg

    fun px(mode: Mode): Int = if (mode == Mode.HOME) res.px else type.px

    val isDepth: Boolean
        get() = type.depth

    /** Camera basis: forward, right, up (the up vector leans on the world's up, or the mount normal when looking straight down). */
    fun basis(): Triple<Vec3, Vec3, Vec3> {
        val f = dir.normalized()
        var r = f.cross(Vec3.UP)
        if (r.length < 1e-3f) r = f.cross(if (mount == Mount.WALL) normal else Vec3.AHEAD)
        if (r.length < 1e-3f) r = Vec3.RIGHT
        r = r.normalized()
        // right-handed: right x forward = up... we want up = right x forward? (f, r, u) with r = f x up => u = r x f
        val u = r.cross(f).normalized()
        return Triple(f, r, u)
    }

    /** Height of the optical centre above the floor at [floorY]. */
    fun heightAbove(floorY: Float): Float = position.y - floorY

    object Dims {
        const val WALL_STANDOFF = 0.08f
        const val DOME_DROP = 0.05f
        const val TRIPOD_HEAD = 0.03f
        const val FLOOR_TRIPOD_DEFAULT = 1.6f
        const val DESK_TRIPOD_DEFAULT = 0.4f
        const val FLOOR_TRIPOD_MIN = 0.3f
        const val FLOOR_TRIPOD_MAX = 2.4f
        const val DESK_TRIPOD_MIN = 0.2f
        const val DESK_TRIPOD_MAX = 1.0f
        const val TRIPOD_STEP = 0.1f
    }
}

/** The work area (design `entities3d.zone`): a box standing on a horizontal plane. */
data class Zone(
    /** Centre of the bottom face, on the plane. */
    val center: Vec3,
    /** Unit direction of the long edge on the floor plane. */
    val along: P2,
    val size: ZoneSize = ZoneSize.M,
    val height: ZoneHeight = ZoneHeight.H30,
) {
    val across: P2
        get() = along.right

    val halfW: Float
        get() = size.w / 2f

    val halfD: Float
        get() = size.d / 2f

    val top: Float
        get() = center.y + height.m

    /** Local (u along, v across) of a Stage point. */
    fun local(p: Vec3): P2 {
        val d = P2.of(p) - P2.of(center)
        return P2(d.dot(along), d.dot(across))
    }

    fun world(u: Float, v: Float, y: Float): Vec3 = (P2.of(center) + along * u + across * v).at(y)

    /** True when the floor projection of [p] lies inside the footprint (grown by [margin]). */
    fun containsFlat(p: Vec3, margin: Float = 0f): Boolean {
        val l = local(p)
        return abs(l.x) <= halfW + margin && abs(l.z) <= halfD + margin
    }

    /** Horizontal distance from the footprint's edge (0 inside). */
    fun edgeDistance(p: Vec3): Float {
        val l = local(p)
        val dx = maxOf(0f, abs(l.x) - halfW)
        val dz = maxOf(0f, abs(l.z) - halfD)
        return kotlin.math.hypot(dx, dz)
    }

    val boxCenter: Vec3
        get() = center + Vec3(0f, height.m / 2f, 0f)

    val rotated: Zone
        get() = copy(along = across)
}

/** A mode's layout: its cameras and its work area. */
data class Layout(val cameras: List<Camera> = emptyList(), val zone: Zone? = null) {
    fun camera(id: Int): Camera? = cameras.firstOrNull { it.id == id }

    fun with(camera: Camera): Layout = copy(cameras = cameras.map { if (it.id == camera.id) camera else it })

    /** The smallest free number 1..8 (design `rules.numbering`). */
    fun nextId(): Int? = (1..MAX_CAMERAS).firstOrNull { id -> cameras.none { it.id == id } }

    companion object {
        const val MAX_CAMERAS = 8
        val EMPTY = Layout()
    }
}

/**
 * Aiming limits per mount (design `entities3d.aimKnob`) and the aim helpers. Angles about the mount:
 * wall yaw is measured from the wall normal, pitch from the horizon; ceiling: the angle from straight
 * down; tripod: pitch only.
 */
object Aim {
    const val WALL_YAW_MAX_DEG = 80f
    const val WALL_PITCH_MIN_DEG = -85f
    const val WALL_PITCH_MAX_DEG = 10f
    const val CEILING_FROM_DOWN_MAX_DEG = 85f
    const val TRIPOD_PITCH_MIN_DEG = -85f
    const val TRIPOD_PITCH_MAX_DEG = 30f

    /** [dir] clamped to what the mount allows. Returns the clamped unit vector and whether it was clamped. */
    fun clamp(camera: Camera, dir: Vec3): Pair<Vec3, Boolean> {
        val d = dir.normalized()
        if (!d.isFinite() || d.length < 0.5f) return camera.dir to true
        return when (camera.mount) {
            Mount.WALL -> {
                val n = Vec3(camera.normal.x, 0f, camera.normal.z).normalized().let { if (it.length < 0.5f) Vec3.AHEAD else it }
                val right = Vec3.UP.cross(n).normalized()
                val flat = Vec3(d.x, 0f, d.z)
                var yaw = if (flat.length < 1e-4f) 0f else Math.toDegrees(atan2(flat.dot(right), flat.dot(n)).toDouble()).toFloat()
                var pitch = Math.toDegrees(asin(d.y.coerceIn(-1f, 1f).toDouble())).toFloat()
                var clamped = false
                if (abs(yaw) > WALL_YAW_MAX_DEG) {
                    yaw = WALL_YAW_MAX_DEG * kotlin.math.sign(yaw)
                    clamped = true
                }
                if (pitch < WALL_PITCH_MIN_DEG) {
                    pitch = WALL_PITCH_MIN_DEG
                    clamped = true
                }
                if (pitch > WALL_PITCH_MAX_DEG) {
                    pitch = WALL_PITCH_MAX_DEG
                    clamped = true
                }
                fromYawPitch(n, right, yaw, pitch) to clamped
            }
            Mount.CEILING -> {
                val down = Vec3(0f, -1f, 0f)
                val ang = Math.toDegrees(kotlin.math.acos(d.dot(down).coerceIn(-1f, 1f).toDouble())).toFloat()
                if (ang <= CEILING_FROM_DOWN_MAX_DEG) d to false
                else {
                    val flat = Vec3(d.x, 0f, d.z).normalized().let { if (it.length < 0.5f) Vec3.AHEAD else it }
                    val a = Math.toRadians(CEILING_FROM_DOWN_MAX_DEG.toDouble())
                    (flat * sin(a).toFloat() + down * cos(a).toFloat()).normalized() to true
                }
            }
            Mount.TRIPOD -> {
                val flat = Vec3(d.x, 0f, d.z).normalized().let { if (it.length < 0.5f) Vec3(camera.dir.x, 0f, camera.dir.z).normalized() else it }.let { if (it.length < 0.5f) Vec3.AHEAD else it }
                var pitch = Math.toDegrees(asin(d.y.coerceIn(-1f, 1f).toDouble())).toFloat()
                var clamped = false
                if (pitch < TRIPOD_PITCH_MIN_DEG) {
                    pitch = TRIPOD_PITCH_MIN_DEG
                    clamped = true
                }
                if (pitch > TRIPOD_PITCH_MAX_DEG) {
                    pitch = TRIPOD_PITCH_MAX_DEG
                    clamped = true
                }
                val p = Math.toRadians(pitch.toDouble())
                (flat * cos(p).toFloat() + Vec3.UP * sin(p).toFloat()).normalized() to clamped
            }
        }
    }

    private fun fromYawPitch(n: Vec3, right: Vec3, yawDeg: Float, pitchDeg: Float): Vec3 {
        val y = Math.toRadians(yawDeg.toDouble())
        val p = Math.toRadians(pitchDeg.toDouble())
        val flat = (n * cos(y).toFloat() + right * sin(y).toFloat()).normalized()
        return (flat * cos(p).toFloat() + Vec3.UP * sin(p).toFloat()).normalized()
    }

    /** The camera turned to look at [target] (clamped). */
    fun lookAt(camera: Camera, target: Vec3): Camera {
        val to = target - camera.position
        if (to.length < 0.05f) return camera
        return camera.copy(dir = clamp(camera, to.normalized()).first)
    }

    /** The camera's axis turned by [deg] about the vertical (taste profile D: thumbstick), clamped. */
    fun turnAboutVertical(camera: Camera, deg: Float): Pair<Camera, Boolean> {
        val q = Quat4.axisAngle(Vec3.UP, Math.toRadians(deg.toDouble()).toFloat())
        val (d, clamped) = clamp(camera, q.rotate(camera.dir))
        return camera.copy(dir = d) to clamped
    }

    /** Yaw (degrees, about the vertical, 0 = -Z) of a direction. */
    fun yawDeg(d: Vec3): Float = Math.toDegrees(atan2(d.x.toDouble(), -d.z.toDouble())).toFloat()

    /** Default direction for a fresh camera: the mount normal (wall), straight down (ceiling), ahead (tripod); then auto-aim. */
    fun initialDir(mount: Mount, normal: Vec3): Vec3 =
        when (mount) {
            Mount.WALL -> (Vec3(normal.x, 0f, normal.z).normalized().let { if (it.length < 0.5f) Vec3.AHEAD else it } * 0.94f + Vec3(0f, -0.34f, 0f)).normalized()
            Mount.CEILING -> Vec3(0f, -1f, 0f)
            Mount.TRIPOD -> Vec3.AHEAD
        }

    /** Thumbstick turn rate: dead zone 0.15, up to 60 deg/s (taste profile D). */
    fun stickRateDeg(x: Float): Float {
        val a = abs(x)
        if (a < STICK_DEAD) return 0f
        val t = (a - STICK_DEAD) / (1f - STICK_DEAD)
        return kotlin.math.sign(x) * t * STICK_MAX_DEG_S
    }

    const val STICK_DEAD = 0.15f
    const val STICK_MAX_DEG_S = 60f
    const val SNAP_DEG = 3f
}

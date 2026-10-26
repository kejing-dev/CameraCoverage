package io.embodit.camcoverage.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Pose of the head in Stage metres. */
data class HeadPose(val position: Vec3, val rotation: Quat4) {
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)

    val up: Vec3
        get() = rotation.rotate(Vec3.UP)

    val right: Vec3
        get() = rotation.rotate(Vec3.RIGHT)

    /** Unit vector of the view direction projected on the floor; [fallback] when looking straight down / up. */
    fun level(fallback: P2 = P2(0f, -1f)): P2 {
        val f = forward
        val l = hypot(f.x, f.z)
        return if (l < 0.05f) fallback else P2(f.x / l, f.z / l)
    }
}

/** How a room panel is turned: yaw about the vertical and pitch towards the viewer, radians; no roll. */
data class PanelFacing(val yaw: Float, val pitch: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw) * Quat4.axisAngle(Vec3.RIGHT, -pitch)

    /** Unit vector out of the panel's face, towards the viewer. */
    val normal: Vec3
        get() = Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))

    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))

    val up: Vec3
        get() = normal.cross(right)
}

/**
 * Facing rule for panels that stand in the room (Spatial Ruler): turn about the vertical towards the
 * head, then pitch towards it (clamped to 60 degrees), never roll. Straight above or below, the last
 * yaw is kept.
 */
object FacePanel {
    private const val MAX_PITCH_RAD = 1.0471976f
    private const val KEEP_YAW_WITHIN_M = 0.1f

    fun facing(panel: Vec3, head: Vec3, lastYaw: Float?): PanelFacing {
        val to = head - panel
        val flat = hypot(to.x, to.z)
        val yaw = if (flat < KEEP_YAW_WITHIN_M && lastYaw != null) lastYaw else if (flat < 1e-4f) 0f else atan2(to.x, to.z)
        val pitch = atan2(to.y, flat).coerceIn(-MAX_PITCH_RAD, MAX_PITCH_RAD)
        return PanelFacing(yaw, pitch)
    }
}

/** A point or direction on the floor plane (Stage x, z). */
data class P2(val x: Float, val z: Float) {
    operator fun plus(o: P2) = P2(x + o.x, z + o.z)

    operator fun minus(o: P2) = P2(x - o.x, z - o.z)

    operator fun times(s: Float) = P2(x * s, z * s)

    operator fun unaryMinus() = P2(-x, -z)

    fun dot(o: P2): Float = x * o.x + z * o.z

    val length: Float
        get() = hypot(x, z)

    fun normalized(): P2 {
        val l = length
        return if (l < 1e-9f) this else P2(x / l, z / l)
    }

    fun at(y: Float): Vec3 = Vec3(x, y, z)

    /** The direction turned by [deg] degrees clockwise seen from above (right of the viewer is +). */
    fun turned(deg: Float): P2 {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        // right-hand of a direction d is (-d.z, d.x) in Stage coordinates (y up, -z ahead)
        val right = P2(-z, x)
        return P2(x * c + right.x * s, z * c + right.z * s)
    }

    /** Right-hand unit vector of this direction, seen from above. */
    val right: P2
        get() = P2(-z, x)

    fun isFinite(): Boolean = x.isFinite() && z.isFinite()

    companion object {
        val ZERO = P2(0f, 0f)

        fun of(v: Vec3) = P2(v.x, v.z)
    }
}

/** Wraps an angle in degrees into (-180, 180]. */
fun wrapDeg(a: Float): Float {
    var v = a
    while (v > 180f) v -= 360f
    while (v <= -180f) v += 360f
    return v
}

/**
 * A detected plane (plane detection): a polygon with its vertices in Stage metres and the platform's
 * semantic label by name (`SemanticLabelType.name`). Winding is not trusted; the normal's sign is
 * arbitrary (wall quads face outwards on the emulator), so every hit test is two-sided.
 */
data class PlaneSurface(val id: String, val semantic: String, val vertices: List<Vec3>) {
    val normal: Vec3 by lazy {
        var best = Vec3.UP
        var bestLen = 0f
        for (i in 1 until vertices.size - 1) {
            val c = (vertices[i] - vertices[0]).cross(vertices[i + 1] - vertices[0])
            if (c.length > bestLen) {
                bestLen = c.length
                best = c
            }
        }
        if (bestLen < 1e-9f) Vec3.UP else best.normalized()
    }

    val centroid: Vec3 by lazy { if (vertices.isEmpty()) Vec3.ZERO else vertices.fold(Vec3.ZERO) { a, v -> a + v } / vertices.size.toFloat() }

    val isVertical: Boolean
        get() = abs(normal.y) < VERTICAL_MAX_NY

    val isHorizontal: Boolean
        get() = abs(normal.y) > HORIZONTAL_MIN_NY

    /** Area of the polygon (fan). */
    val area: Float by lazy {
        var a = Vec3.ZERO
        for (i in 1 until vertices.size - 1) a = a + (vertices[i] - vertices[0]).cross(vertices[i + 1] - vertices[0])
        a.length / 2f
    }

    /** `SEMANTIC:uuid8`, used in entity names and logs. */
    val label: String
        get() = "$semantic:${id.take(8)}"

    /** Two-sided ray / polygon hit (Moeller-Trumbore over the fan). [direction] is a unit vector. */
    fun hit(origin: Vec3, direction: Vec3, maxDistance: Float = 20f): SurfaceHit? {
        var best: SurfaceHit? = null
        for (i in 1 until vertices.size - 1) {
            val v0 = vertices[0]
            val e1 = vertices[i] - v0
            val e2 = vertices[i + 1] - v0
            val pv = direction.cross(e2)
            val det = e1.dot(pv)
            if (abs(det) < 1e-9f) continue
            val inv = 1f / det
            val tv = origin - v0
            val u = tv.dot(pv) * inv
            if (u < -EDGE_EPS || u > 1f + EDGE_EPS) continue
            val qv = tv.cross(e1)
            val w = direction.dot(qv) * inv
            if (w < -EDGE_EPS || u + w > 1f + EDGE_EPS) continue
            val t = e2.dot(qv) * inv
            if (t <= MIN_DISTANCE || t > maxDistance) continue
            if (best == null || t < best.distance) {
                var n = normal
                if (n.dot(direction) > 0f) n = -n
                best = SurfaceHit(origin + direction * t, n, t, this)
            }
        }
        return best
    }

    /** Hit of the ray with the unbounded plane of this surface. */
    fun hitInfinite(origin: Vec3, direction: Vec3): SurfaceHit? {
        if (vertices.isEmpty()) return null
        var n = normal
        val denom = direction.dot(n)
        if (abs(denom) < 1e-6f) return null
        val t = (vertices[0] - origin).dot(n) / denom
        if (t <= MIN_DISTANCE) return null
        if (denom > 0f) n = -n
        return SurfaceHit(origin + direction * t, n, t, this)
    }

    /** Orthogonal projection of [point] onto the plane. */
    fun project(point: Vec3): Vec3 {
        if (vertices.isEmpty()) return point
        val n = normal
        return point - n * (point - vertices[0]).dot(n)
    }

    /** True when the projection of [point] lies inside the polygon, shrunk by [inset] metres. */
    fun contains(point: Vec3, inset: Float = 0f): Boolean {
        val p = project(point)
        val n = normal
        val sign = polygonSign()
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            val e = b - a
            val len = e.length
            if (len < 1e-9f) continue
            val side = e.cross(p - a).dot(n) / len * sign
            if (side < inset) return false
        }
        return true
    }

    /** Signed distance of [point]'s projection to the nearest edge (positive inside). */
    fun edgeDistance(point: Vec3): Float {
        val p = project(point)
        val n = normal
        val sign = polygonSign()
        var min = Float.MAX_VALUE
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            val e = b - a
            val len = e.length
            if (len < 1e-9f) continue
            val side = e.cross(p - a).dot(n) / len * sign
            if (side < min) min = side
        }
        return min
    }

    /** Pushes [point]'s projection inside the polygon, [inset] from every edge, by clamping against each edge. */
    fun clampInside(point: Vec3, inset: Float): Vec3 {
        var p = project(point)
        val n = normal
        val sign = polygonSign()
        repeat(2) {
            for (i in vertices.indices) {
                val a = vertices[i]
                val b = vertices[(i + 1) % vertices.size]
                val e = b - a
                val len = e.length
                if (len < 1e-9f) continue
                val inward = n.cross(e).normalized() * sign
                val side = (p - a).dot(inward)
                if (side < inset) p = p + inward * (inset - side)
            }
        }
        return p
    }

    private fun polygonSign(): Float {
        // orientation of the polygon's winding relative to the normal
        var a = Vec3.ZERO
        for (i in 1 until vertices.size - 1) a = a + (vertices[i] - vertices[0]).cross(vertices[i + 1] - vertices[0])
        return if (a.dot(normal) >= 0f) 1f else -1f
    }

    companion object {
        const val VERTICAL_MAX_NY = 0.2f
        const val HORIZONTAL_MIN_NY = 0.9f
        private const val EDGE_EPS = 1e-5f
        private const val MIN_DISTANCE = 1e-4f
    }
}

/** Result of a ray / surface intersection. [normal] faces the ray origin. */
data class SurfaceHit(val point: Vec3, val normal: Vec3, val distance: Float, val plane: PlaneSurface)

package io.embodit.camcoverage.ui.cover.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import io.embodit.camcoverage.domain.Camera
import io.embodit.camcoverage.domain.Cell
import io.embodit.camcoverage.domain.ConeGeom
import io.embodit.camcoverage.domain.ConeRays
import io.embodit.camcoverage.domain.Dori
import io.embodit.camcoverage.domain.Mode
import io.embodit.camcoverage.domain.Mount
import io.embodit.camcoverage.domain.PlaneSurface
import io.embodit.camcoverage.domain.Quat4
import io.embodit.camcoverage.domain.Vec3
import io.embodit.camcoverage.domain.Zone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** How a camera is drawn this frame (design `entities3d.camera`, `recLight`, `coneEdges`, `coneVolume`). */
internal enum class Look { NORMAL, SELECTED, BLOCKED, STALE }

/** Camera body dimensions in metres (design `primitiveRecipes`). */
internal object CamDims {
    const val BASE_D = 0.08f
    const val BASE_T = 0.015f
    const val ARM = 0.08f
    const val ARM_W = 0.02f
    const val BODY_D = 0.06f
    const val BODY_L = 0.16f
    const val HOOD_W = 0.08f
    const val HOOD_T = 0.006f
    const val HOOD_L = 0.18f
    const val LENS_D = 0.04f
    const val REC_D = 0.012f
    const val DOME_BASE_D = 0.12f
    const val DOME_BASE_T = 0.02f
    const val DOME_D = 0.10f
    const val BAR_L = 0.09f
    const val BAR_H = 0.025f
    const val POLE_D = 0.025f
    const val LEG_D = 0.015f
    const val LEG_FLOOR = 0.40f
    const val LEG_DESK = 0.15f
    const val HEAD = 0.04f
    const val HEAD_H = 0.03f
    const val KNOB_D = 0.05f
    const val KNOB_M = 0.6f
    const val EDGE_W = 0.006f
    const val TAG_ABOVE = 0.08f
}

/**
 * One camera: body by mount (bullet / dome / bar + tripod), REC light, four cone edges, the cone volume
 * and the footprint (shared with the mini room), the aim knob and the hit box.
 */
internal class CameraView(private val root: Entity, mini: Entity, private val meshes: CcMeshes, private val m: CcMaterials, val id: Int) {
    private val parts = ArrayList<Part>()
    private val base = part(meshes.cylinder, m.camBody, "camBase")
    private val arm = part(meshes.cube, m.camBody, "camArm")
    private val body = part(meshes.cylinder, m.camBody, "camBody")
    private val hood = part(meshes.cube, m.camBody, "camHood")
    private val lens = part(meshes.disc, m.camDark, "camLens")
    private val lens2 = part(meshes.disc, m.camDark, "camLens2")
    private val emitter = part(meshes.disc, m.camDark, "camEmitter")
    private val dome = part(meshes.sphere, m.dome, "camDome")
    private val pole = part(meshes.cylinder, m.camBody, "camPole")
    private val legs = List(3) { part(meshes.cylinder, m.camDark, "camLeg$it") }
    private val head = part(meshes.cube, m.camDark, "camHead")
    private val rec = part(meshes.sphere, m.recIdle, "camRec")
    private val knob = part(meshes.sphere, m.knob, "knob")
    private val knobRim = part(meshes.sphere, m.knobRim, "knobRim")
    private val edges = List(4) { Strip(root, meshes.band, m.coneEdge, "coneEdge:$id:$it") }
    private val cone = DynMesh(listOf(root, mini), m.coneFill, "cone:$id")
    private val foot = DynMesh(listOf(root, mini), m.footFill, "foot:$id")
    private val footEdge = DynMesh(listOf(root), m.footEdge, "footEdge:$id")
    private val miniBody = Part(mini, meshes.cube, m.miniBody, "miniCam:$id")
    private val hit = Entity()
    private val knobHit = Entity()
    private var hitSize: Vec3? = null
    private var knobOn = false
    private var recBlink = false
    private var lastRecOn = true
    var geom: ConeGeom? = null
        private set

    init {
        runCatching { hit.setName("camHit:$id") }
        hit.components.set(InteractableComponent())
        root.addChild(hit)
        runCatching { knobHit.setName("knob:$id") }
        knobHit.components.set(boxCollision(Vec3(CamDims.KNOB_D * 1.6f, CamDims.KNOB_D * 1.6f, CamDims.KNOB_D * 1.6f)))
        knobHit.components.set(InteractableComponent())
        root.addChild(knobHit)
        knobHit.enabled = false
        hideBody()
    }

    private fun part(mesh: com.pico.spatial.core.ecs.resource.MeshResource, mat: com.pico.spatial.core.ecs.resource.Material, name: String): Part = Part(root, mesh, mat, "$name:$id").also { parts += it }

    fun ownsBody(e: Entity): Boolean = e.id == hit.id || e.id == miniBody.entity.id || parts.any { it !== knob && it !== knobRim && it.entity.id == e.id }

    fun ownsKnob(e: Entity): Boolean = e.id == knobHit.id || e.id == knob.entity.id || e.id == knobRim.entity.id

    private fun hideBody() = parts.forEach { it.show(false) }

    /** Draws the body of [c] (every part placed, the unused ones hidden). */
    fun placeBody(c: Camera, mode: Mode, look: Look, floorY: Float) {
        hideBody()
        val stale = look == Look.STALE
        val bodyMat = if (stale) m.camStale else m.camBody
        val darkMat = if (stale) m.camStale else m.camDark
        val (f, r, u) = c.basis()
        val axisRot = Quat4.fromBasis(r, f, u) // local Y along the optical axis (cylinders run along Y)
        val p = c.position
        when (c.mount) {
            Mount.WALL -> {
                val n = c.normal
                base.material(bodyMat)
                base.place(c.mountPoint + n * (CamDims.BASE_T / 2f), Vec3(CamDims.BASE_D, CamDims.BASE_T, CamDims.BASE_D), Quat4.fromTo(Vec3.UP, n))
                arm.material(bodyMat)
                arm.place(c.mountPoint + n * (CamDims.BASE_T + CamDims.ARM / 2f), Vec3(CamDims.ARM_W, CamDims.ARM_W, CamDims.ARM), Quat4.fromTo(Vec3(0f, 0f, 1f), n))
                bullet(p, f, u, axisRot, bodyMat, darkMat)
            }
            Mount.CEILING -> {
                val n = c.normal // down
                base.material(bodyMat)
                base.place(c.mountPoint + n * (CamDims.DOME_BASE_T / 2f), Vec3(CamDims.DOME_BASE_D, CamDims.DOME_BASE_T, CamDims.DOME_BASE_D), Quat4.IDENTITY)
                dome.material(if (stale) m.stale else m.dome)
                dome.place(c.mountPoint + n * CamDims.DOME_BASE_T, Vec3(CamDims.DOME_D, CamDims.DOME_D, CamDims.DOME_D))
                rec.place(c.mountPoint + n * (CamDims.DOME_BASE_T / 2f) + Vec3(f.x, 0f, f.z).normalized().let { if (it.length < 0.5f) Vec3.AHEAD else it } * (CamDims.DOME_BASE_D / 2f), Vec3(CamDims.REC_D, CamDims.REC_D, CamDims.REC_D))
            }
            Mount.TRIPOD -> {
                val legLen = if (c.onFloor) CamDims.LEG_FLOOR else CamDims.LEG_DESK
                val mp = c.mountPoint
                pole.material(bodyMat)
                pole.place(mp + Vec3(0f, c.height / 2f, 0f), Vec3(CamDims.POLE_D, c.height, CamDims.POLE_D))
                for (i in 0 until 3) {
                    val a = 2.0 * PI * i / 3.0
                    val out = Vec3(cos(a).toFloat(), 0f, sin(a).toFloat())
                    val top = mp + Vec3(0f, legLen * cos(Math.toRadians(30.0)).toFloat(), 0f)
                    val bottom = mp + out * (legLen * sin(Math.toRadians(30.0)).toFloat())
                    val mid = (top + bottom) * 0.5f
                    legs[i].material(darkMat)
                    legs[i].place(mid, Vec3(CamDims.LEG_D, legLen, CamDims.LEG_D), Quat4.fromTo(Vec3.UP, (top - bottom).normalized()))
                }
                head.material(darkMat)
                head.place(mp + Vec3(0f, c.height + CamDims.HEAD_H / 2f, 0f), Vec3(CamDims.HEAD, CamDims.HEAD_H, CamDims.HEAD))
                if (mode == Mode.HOME) bullet(p, f, u, axisRot, bodyMat, darkMat)
                else {
                    // bar camera (design `primitiveRecipes.bar`), two lenses (+ an emitter for depth types)
                    body.material(bodyMat)
                    body.place(p, Vec3(CamDims.BAR_L, CamDims.BAR_H, CamDims.BAR_H), Quat4.fromBasis(r, u, -f))
                    val front = p + f * (CamDims.BAR_H / 2f + 0.001f)
                    lens.material(darkMat)
                    lens.placeFacing(front + r * 0.025f, Vec3(0.015f, 0.015f, 1f), f)
                    lens2.material(darkMat)
                    lens2.placeFacing(front - r * 0.025f, Vec3(0.015f, 0.015f, 1f), f)
                    if (c.isDepth) {
                        emitter.material(darkMat)
                        emitter.placeFacing(front, Vec3(0.01f, 0.01f, 1f), f)
                    }
                    rec.place(front + u * 0.008f + r * 0.04f, Vec3(CamDims.REC_D, CamDims.REC_D, CamDims.REC_D))
                }
            }
        }
        miniBody.place(p, Vec3(0.25f, 0.25f, 0.25f))
        miniBody.material(if (stale) m.stale else if (look == Look.BLOCKED) m.coneEdgeBlocked else if (look == Look.SELECTED) m.coneEdgeSelected else m.miniBody)
        collider(p, Vec3(0.22f, 0.16f, 0.22f))
    }

    private fun bullet(p: Vec3, f: Vec3, u: Vec3, axisRot: Quat4, bodyMat: com.pico.spatial.core.ecs.resource.Material, darkMat: com.pico.spatial.core.ecs.resource.Material) {
        body.material(bodyMat)
        body.place(p, Vec3(CamDims.BODY_D, CamDims.BODY_L, CamDims.BODY_D), axisRot)
        hood.material(bodyMat)
        hood.place(p + u * (CamDims.BODY_D / 2f + CamDims.HOOD_T / 2f) + f * 0.01f, Vec3(CamDims.HOOD_W, CamDims.HOOD_T, CamDims.HOOD_L), Quat4.fromBasis(f.cross(u).normalized(), u, -f))
        lens.material(darkMat)
        lens.placeFacing(p + f * (CamDims.BODY_L / 2f + 0.003f), Vec3(CamDims.LENS_D, CamDims.LENS_D, 1f), f)
        rec.place(p + f * (CamDims.BODY_L / 2f - 0.01f) + u * (CamDims.BODY_D / 2f + 0.002f), Vec3(CamDims.REC_D, CamDims.REC_D, CamDims.REC_D))
    }

    /** REC light (design `entities3d.recLight`): off / on / 1 Hz blink while blocked / grey while stale. */
    fun rec(on: Boolean, blink: Boolean, stale: Boolean, nowMs: Long) {
        if (stale) {
            rec.material(m.recStale)
            return
        }
        recBlink = blink
        val lit = if (blink) (nowMs / 500L) % 2L == 0L else on
        if (lit != lastRecOn) {
            lastRecOn = lit
            rec.material(if (lit) m.recOn else m.recIdle)
        } else rec.material(if (lit) m.recOn else m.recIdle)
    }

    /** Only the materials of the cone (look / inside changed): no mesh work. */
    fun restyle(g: ConeGeom, look: Look, inside: Boolean, mode: Mode) {
        val edgeMat = when (look) { Look.SELECTED -> m.coneEdgeSelected; Look.BLOCKED -> m.coneEdgeBlocked; Look.STALE -> m.coneEdgeStale; else -> m.coneEdge }
        for (i in 0 until 4) edges[i].material(edgeMat)
        cone.material(when (look) { Look.SELECTED -> m.coneFillSelected; Look.BLOCKED -> m.coneFillBlocked; Look.STALE -> m.coneFillStale; else -> if (inside) m.coneFillInside else m.coneFill })
        foot.show(mode == Mode.WORKCELL && look != Look.STALE)
        footEdge.material(if (look == Look.SELECTED) m.footEdgeSelected else if (look == Look.STALE) m.coneEdgeStale else m.footEdge)
    }

    /** The cone: edges, volume, footprint; [inside] = the viewer's head is in this cone (sides go faint). */
    fun cone(g: ConeGeom, look: Look, inside: Boolean, mode: Mode, nowMs: Long) {
        geom = g
        val edgeMat = when (look) { Look.SELECTED -> m.coneEdgeSelected; Look.BLOCKED -> m.coneEdgeBlocked; Look.STALE -> m.coneEdgeStale; else -> m.coneEdge }
        val corners = g.corners
        for (i in 0 until 4) {
            edges[i].material(edgeMat)
            edges[i].draw(g.origin, corners[i], CamDims.EDGE_W, Vec3.UP)
        }
        val fill = when (look) { Look.SELECTED -> m.coneFillSelected; Look.BLOCKED -> m.coneFillBlocked; Look.STALE -> m.coneFillStale; else -> if (inside) m.coneFillInside else m.coneFill }
        cone.material(fill)
        val b = MeshBuilder()
        val o = b.vertex(g.origin)
        val ring = ringIndices()
        val ringV = ring.map { (i, j) -> b.vertex(g.at(i, j)) }
        for (k in ringV.indices) b.tri(o, ringV[k], ringV[(k + 1) % ringV.size])
        cone.set(b)
        // footprint: 8 x 5 cells, skipped where the corners hit different planes or the depth jumps
        val fb = MeshBuilder()
        val eb = MeshBuilder()
        for (j in 0 until ConeRays.ROWS - 1) for (i in 0 until ConeRays.COLS - 1) {
            val ids = listOf(g.planeAt(i, j), g.planeAt(i + 1, j), g.planeAt(i + 1, j + 1), g.planeAt(i, j + 1))
            if (ids.any { it == null } || ids.toSet().size != 1) continue
            val pts = listOf(g.at(i, j), g.at(i + 1, j), g.at(i + 1, j + 1), g.at(i, j + 1))
            val d = pts.map { it.distanceTo(g.origin) }
            if (d.max() - d.min() > 0.3f + 0.25f * d.min()) continue
            val n = g.normalAt(i, j) ?: continue
            val off = n * 0.01f
            fb.quad(pts[0] + off, pts[1] + off, pts[2] + off, pts[3] + off)
            // outline on the outer edges of the grid
            if (j == 0) ribbon(eb, pts[0] + off, pts[1] + off, n)
            if (j == ConeRays.ROWS - 2) ribbon(eb, pts[3] + off, pts[2] + off, n)
            if (i == 0) ribbon(eb, pts[0] + off, pts[3] + off, n)
            if (i == ConeRays.COLS - 2) ribbon(eb, pts[1] + off, pts[2] + off, n)
        }
        foot.set(if (fb.isEmpty) null else fb)
        foot.show(mode == Mode.WORKCELL && look != Look.STALE)
        footEdge.material(if (look == Look.SELECTED) m.footEdgeSelected else if (look == Look.STALE) m.coneEdgeStale else m.footEdge)
        footEdge.set(if (eb.isEmpty) null else eb)
    }

    private fun ribbon(b: MeshBuilder, a: Vec3, c: Vec3, n: Vec3) {
        val dir = c - a
        if (dir.length < 1e-4f) return
        val side = n.cross(dir.normalized()).normalized() * 0.01f
        b.quad(a - side, c - side, c + side, a + side)
    }

    private fun ringIndices(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until ConeRays.COLS) out += i to 0
        for (j in 1 until ConeRays.ROWS) out += (ConeRays.COLS - 1) to j
        for (i in ConeRays.COLS - 2 downTo 0) out += i to (ConeRays.ROWS - 1)
        for (j in ConeRays.ROWS - 2 downTo 1) out += 0 to j
        return out
    }

    /** The aim knob 0.6 m along the axis (selected only); [shake] jitters it at the limit. */
    fun knob(c: Camera?, nowMs: Long, shake: Boolean) {
        if (c == null) {
            if (knobOn) {
                knob.show(false)
                knobRim.show(false)
                knobHit.enabled = false
                knobOn = false
            }
            return
        }
        var at = c.position + c.dir * CamDims.KNOB_M
        if (shake) at = at + Vec3.UP * (0.004f * sin(nowMs / 25.0).toFloat())
        knob.place(at, Vec3(CamDims.KNOB_D, CamDims.KNOB_D, CamDims.KNOB_D))
        knobRim.place(at, Vec3(CamDims.KNOB_D * 1.12f, CamDims.KNOB_D * 1.12f, CamDims.KNOB_D * 1.12f))
        knobHit.setLocal(position = at)
        if (!knobOn) {
            knobHit.enabled = true
            knobOn = true
        }
    }

    private fun collider(center: Vec3, size: Vec3) {
        if (hitSize == null || hitSize!!.distanceTo(size) > 0.001f) {
            hit.components.set(boxCollision(size))
            hitSize = size
        }
        hit.setLocal(position = center)
    }

    fun hideAll() {
        hideBody()
        edges.forEach { it.hide() }
        cone.show(false)
        foot.show(false)
        footEdge.show(false)
        miniBody.show(false)
        knob(null, 0L, false)
        hit.enabled = false
    }

    fun showAll() {
        cone.show(true)
        footEdge.show(true)
        hit.enabled = true
    }

    fun destroy() {
        parts.forEach { it.destroy() }
        edges.forEach { it.destroy() }
        cone.destroy()
        foot.destroy()
        footEdge.destroy()
        miniBody.destroy()
        runCatching { hit.destroy(true) }
        runCatching { knobHit.destroy(true) }
    }
}

/** The work area (design `entities3d.zone`, `zoneHit`): 12 edges, 4 corner marks, an invisible hit box. */
internal class ZoneView(root: Entity, mini: Entity, meshes: CcMeshes, private val m: CcMaterials) {
    private val edges = List(12) { Strip(root, meshes.band, m.zoneEdge, "zoneEdge:$it") }
    private val corners = List(4) { Part(root, meshes.cube, m.zoneCorner, "zoneCorner:$it") }
    private val miniEdges = List(4) { Strip(mini, meshes.band, m.zoneEdge, "miniZone:$it") }
    private val hit = Entity()
    private var hitSize: Vec3? = null
    private var shown = false

    init {
        runCatching { hit.setName("zoneHit") }
        hit.components.set(InteractableComponent())
        root.addChild(hit)
        hide()
    }

    fun owns(e: Entity): Boolean = e.id == hit.id

    fun draw(z: Zone, saving: Boolean, hitEnabled: Boolean, nowMs: Long) {
        val a = z.along.at(0f)
        val c = z.across.at(0f)
        val y0 = z.center.y + 0.003f
        val y1 = z.top
        val base = P(z, a, c)
        // bottom and top rectangles, four uprights
        val lo = listOf(base.corner(-1f, -1f, y0), base.corner(1f, -1f, y0), base.corner(1f, 1f, y0), base.corner(-1f, 1f, y0))
        val hi = lo.map { Vec3(it.x, y1, it.z) }
        for (i in 0 until 4) {
            edges[i].draw(lo[i], lo[(i + 1) % 4], 0.006f, Vec3.UP)
            edges[4 + i].draw(hi[i], hi[(i + 1) % 4], 0.006f, Vec3.UP)
            edges[8 + i].draw(lo[i], hi[i], 0.006f, a)
            corners[i].material(if (saving) m.zoneCornerSaving else m.zoneCorner)
            corners[i].place(hi[i], Vec3(0.03f, 0.03f, 0.03f), if (saving) Quat4.axisAngle(Vec3.UP, (nowMs % 4000L) / 4000f * 2f * PI.toFloat()) else Quat4.IDENTITY)
            miniEdges[i].draw(lo[i], lo[(i + 1) % 4], 0.08f, Vec3.UP)
        }
        val size = Vec3(z.size.w, z.height.m, z.size.d)
        if (hitSize == null || hitSize!!.distanceTo(size) > 0.001f) {
            hit.components.set(boxCollision(Vec3(size.x, size.y, size.z)))
            hitSize = size
        }
        hit.setLocal(position = z.boxCenter, rotation = Quat4.axisAngle(Vec3.UP, kotlin.math.atan2(a.x, a.z)))
        hit.enabled = hitEnabled
        shown = true
    }

    private class P(val z: Zone, val a: Vec3, val c: Vec3) {
        fun corner(u: Float, v: Float, y: Float): Vec3 = Vec3(z.center.x, y, z.center.z) + a * (u * z.halfW) + c * (v * z.halfD)
    }

    fun hide() {
        if (!shown && hitSize != null) return
        edges.forEach { it.hide() }
        corners.forEach { it.show(false) }
        miniEdges.forEach { it.hide() }
        hit.enabled = false
        shown = false
    }

    fun destroy() {
        edges.forEach { it.destroy() }
        corners.forEach { it.destroy() }
        miniEdges.forEach { it.destroy() }
        runCatching { hit.destroy(true) }
    }
}

/** A set of cells drawn as one mesh per band (design: only the bands that changed are rebuilt). */
internal class BandGrid(hosts: List<Entity>, private val bands: List<com.pico.spatial.core.ecs.resource.Material>, private val outlineBands: Set<Int>, private val name: String) {
    private val meshes = bands.indices.map { DynMesh(hosts, bands[it], "$name:$it") }
    private var last: List<List<Cell>> = bands.indices.map { emptyList() }
    private val outline = DynMesh(hosts, bands.last(), "$name:outline")
    private var lastOutline: List<Cell> = emptyList()

    fun outlineMaterial(m: com.pico.spatial.core.ecs.resource.Material) = outline.material(m)

    /** [cells] grouped by band; [downgraded] cells get an extra outline (the body's shadow). Builds everything now. */
    fun set(cells: List<Cell>, downgraded: List<Cell> = emptyList()) = plan(cells, downgraded).forEach { it.second() }

    /**
     * The work of [set] as one item per changed band (key, build), so that the scene can spread the mesh
     * creations over frames: a mesh costs about one rendered frame on the idle emulator and twenty at once
     * stall the main thread.
     */
    fun plan(cells: List<Cell>, downgraded: List<Cell> = emptyList()): List<Pair<String, () -> Unit>> {
        val out = ArrayList<Pair<String, () -> Unit>>()
        for (b in bands.indices) {
            val mine = cells.filter { it.band == b }
            if (mine == last[b]) continue
            last = last.toMutableList().also { it[b] = mine }
            out += "$name:$b" to {
                if (mine.isEmpty()) meshes[b].set(null)
                else {
                    val mb = MeshBuilder()
                    for (c in mine) {
                        if (b in outlineBands) mb.outline(c.center + c.normal * 0.003f, c.right, c.up, c.halfW, c.halfH, 0.03f)
                        else mb.rect(c.center + c.normal * 0.003f, c.right, c.up, c.halfW, c.halfH)
                    }
                    meshes[b].set(mb)
                }
            }
        }
        if (downgraded != lastOutline) {
            lastOutline = downgraded
            out += "$name:outline" to {
                if (downgraded.isEmpty()) outline.set(null)
                else {
                    val mb = MeshBuilder()
                    for (c in downgraded) mb.outline(c.center + c.normal * 0.005f, c.right, c.up, c.halfW, c.halfH, 0.004f)
                    outline.set(mb)
                }
            }
        }
        return out
    }

    fun show(visible: Boolean) {
        meshes.forEach { it.show(visible) }
        outline.show(visible)
    }

    fun clear() = set(emptyList())

    fun planClear(): List<Pair<String, () -> Unit>> = plan(emptyList())

    fun destroy() {
        meshes.forEach { it.destroy() }
        outline.destroy()
    }
}

/** The viewer's ring on the floor (design `entities3d.youRing`) with up to 8 notches = cameras that see them. */
internal class YouRingView(root: Entity, mini: Entity, meshes: CcMeshes, private val m: CcMaterials) {
    private val ring = Part(root, meshes.ring, m.ringNeutral, "youRing")
    private val notches = List(8) { Part(root, meshes.cube, m.notch, "youNotch:$it") }
    private val miniHead = Part(mini, meshes.sphere, m.ringNeutral, "miniHead")
    private val miniTorso = Part(mini, meshes.cube, m.ringNeutral, "miniTorso")
    private var on = false

    fun draw(head: Vec3, floorY: Float, material: com.pico.spatial.core.ecs.resource.Material, seenBy: Int, forward: Vec3) {
        on = true
        ring.material(material)
        ring.place(Vec3(head.x, floorY + 0.004f, head.z), Vec3(1.0f, 1f, 1.0f))
        for (i in notches.indices) {
            if (i < seenBy) {
                val a = 2.0 * PI * i / maxOf(1, seenBy)
                notches[i].place(Vec3(head.x + (cos(a) * 0.47).toFloat(), floorY + 0.006f, head.z + (sin(a) * 0.47).toFloat()), Vec3(0.06f, 0.004f, 0.02f), Quat4.axisAngle(Vec3.UP, -a.toFloat()))
            } else notches[i].show(false)
        }
        miniHead.material(material)
        miniHead.place(head, Vec3(0.26f, 0.26f, 0.26f))
        miniTorso.material(material)
        val f = Vec3(forward.x, 0f, forward.z).normalized().let { if (it.length < 0.5f) Vec3.AHEAD else it }
        miniTorso.place(head - Vec3(0f, 0.75f, 0f), Vec3(0.42f, 1.1f, 0.26f), Quat4.fromBasis(Vec3.UP.cross(f).normalized(), Vec3.UP, f))
    }

    fun hide() {
        if (!on) return
        on = false
        ring.show(false)
        notches.forEach { it.show(false) }
        miniHead.show(false)
        miniTorso.show(false)
    }
}

/** The detected planes as faint quads in the mini room, and dashed hint frames on them in the room (S04 / S07). */
internal class PlaneViews(private val root: Entity, private val mini: Entity, private val meshes: CcMeshes, private val m: CcMaterials) {
    private val miniQuads = HashMap<String, Part>()
    private val frames = HashMap<String, Frame>()
    private var framesOn = HashSet<String>()

    fun sync(planes: Collection<PlaneSurface>, fit: (PlaneSurface) -> Triple<Vec3, Vec3, Quat4>?) {
        val seen = HashSet<String>()
        for (p in planes) {
            seen += p.id
            val f = fit(p) ?: continue
            val q = miniQuads.getOrPut(p.id) { Part(mini, meshes.cube, m.miniPlane, "miniPlane:${p.label}") }
            q.place(f.first, Vec3(f.second.x, f.second.y, 0.02f), f.third)
        }
        for (id in miniQuads.keys.filter { it !in seen }) miniQuads.remove(id)?.destroy()
        for (id in frames.keys.filter { it !in seen }) frames.remove(id)?.destroy()
    }

    /** Dashed frames on [planes] (empty = none). */
    fun hint(planes: Collection<PlaneSurface>, material: com.pico.spatial.core.ecs.resource.Material, fit: (PlaneSurface) -> Triple<Vec3, Vec3, Quat4>?) {
        val want = HashSet<String>()
        for (p in planes) {
            val f = fit(p) ?: continue
            want += p.id
            val fr = frames.getOrPut(p.id) { Frame(root, meshes.dashes, material, "planeHint:${p.label}") }
            fr.material(material)
            val rot = f.third
            val right = rot.rotate(Vec3.RIGHT)
            val up = rot.rotate(Vec3.UP)
            val n = rot.rotate(Vec3(0f, 0f, 1f))
            fr.draw(f.first + n * 0.008f, right, up, f.second.x / 2f - 0.02f, f.second.y / 2f - 0.02f, 0.012f, n)
        }
        for (id in framesOn.filter { it !in want }) frames[id]?.hide()
        framesOn = want
    }

    fun clear() {
        miniQuads.values.forEach { it.destroy() }
        miniQuads.clear()
        frames.values.forEach { it.destroy() }
        frames.clear()
    }
}

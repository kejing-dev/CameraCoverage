package io.embodit.camcoverage.ui.cover.scene

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.camcoverage.domain.PlaneSurface
import io.embodit.camcoverage.domain.Quat4
import io.embodit.camcoverage.domain.Vec3
import io.embodit.camcoverage.platform.CcLog
import io.embodit.camcoverage.ui.theme.CcColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres: every entity hangs below the calibrated
// stage root, whose local space equals Stage space (Spatial Ruler).
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children. */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

/** Metres per dp of an attachment panel at scale 1 (Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

internal fun boxCollision(size: Vec3) = CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(size.x, size.y, size.z))), physicsMaterial = PhysicsMaterialResource())

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user is
 * destroyed (Spatial Ruler: deleting one pin closed the materials of all pins), so shared ones are made
 * global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/** Programmatic meshes, built once per scene (creating a mesh costs about one rendered frame). */
internal object MeshFactory {
    private val FRONT = Vector3(0f, 0f, 1f)
    private val UP = Vector3(0f, 1f, 0f)

    /** A unit cube centred on the origin (scaled to any box). */
    fun unitCube(name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val faces = listOf(Vector3(1f, 0f, 0f), Vector3(-1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, -1f, 0f), Vector3(0f, 0f, 1f), Vector3(0f, 0f, -1f))
        for (n in faces) {
            val nn = Vec3(n.x, n.y, n.z)
            val u = (if (abs(n.y) > 0.5f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)).let { it.rejectFrom(nn).normalized() }
            val v = nn.cross(u)
            val base = positions.size
            for ((a, b) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val p = (nn + u * a + v * b) * 0.5f
                positions += Vector3(p.x, p.y, p.z)
                normals += n
            }
            indices += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }

    /** A unit square (local XZ, centred, facing +Y): floor / ceiling patches. */
    fun unitSquare(name: String): MeshResource {
        val positions = listOf(Vector3(-0.5f, 0f, -0.5f), Vector3(0.5f, 0f, -0.5f), Vector3(0.5f, 0f, 0.5f), Vector3(-0.5f, 0f, 0.5f))
        val model = MeshModel(positions = positions, triangleIndices = listOf(0, 2, 1, 0, 3, 2), normals = List(4) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /** Strip x 0..1, y -0.5..0.5 facing +Z, cut into [dashes] (0 = solid): scaled to any length and width. */
    fun unitBand(name: String, dashes: Int = 0, fill: Float = 0.55f): MeshResource {
        val rects = if (dashes <= 0) listOf(0f to 1f) else List(dashes) { i -> val p = 1f / dashes; (i * p + p * (1f - fill) / 2f) to (i * p + p * (1f + fill) / 2f) }
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for ((x0, x1) in rects) {
            val b = positions.size
            positions += listOf(Vector3(x0, -0.5f, 0f), Vector3(x1, -0.5f, 0f), Vector3(x1, 0.5f, 0f), Vector3(x0, 0.5f, 0f))
            indices += listOf(b, b + 1, b + 2, b, b + 2, b + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { FRONT })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)), name = name)
    }

    /** Unit disc (radius 0.5) in the local XY plane facing +Z: speaker faces, ceiling pucks, dots. */
    fun unitDisc(name: String, segments: Int = 36): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            positions += Vector3((cos(a) * 0.5).toFloat(), (sin(a) * 0.5).toFloat(), 0f)
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + i
            indices += 1 + (i + 1) % segments
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { FRONT })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.001f)), name = name)
    }

    /** Flat ring (inner 0.44, outer 0.5) in the local XZ plane facing +Y: the seat mark. */
    fun unitRing(name: String, inner: Float = 0.44f, segments: Int = 48): MeshResource {
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0 until segments) {
            val s = 2 * PI * i / segments
            val e = 2 * PI * (i + 1) / segments
            val b = positions.size
            positions += Vector3((cos(s) * inner).toFloat(), 0f, (sin(s) * inner).toFloat())
            positions += Vector3((cos(s) * 0.5).toFloat(), 0f, (sin(s) * 0.5).toFloat())
            positions += Vector3((cos(e) * 0.5).toFloat(), 0f, (sin(e) * 0.5).toFloat())
            positions += Vector3((cos(e) * inner).toFloat(), 0f, (sin(e) * inner).toFloat())
            indices += listOf(b, b + 2, b + 1, b, b + 3, b + 2)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /** Low-poly unit sphere (diameter 1): the ear-height ball. */
    fun unitSphere(name: String, rings: Int = 8, sectors: Int = 12): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (r in 0..rings) {
            val phi = PI * r / rings
            for (s in 0..sectors) {
                val theta = 2 * PI * s / sectors
                val n = Vector3((sin(phi) * cos(theta)).toFloat(), cos(phi).toFloat(), (sin(phi) * sin(theta)).toFloat())
                positions += Vector3(n.x * 0.5f, n.y * 0.5f, n.z * 0.5f)
                normals += n
            }
        }
        for (r in 0 until rings) for (s in 0 until sectors) {
            val a = r * (sectors + 1) + s
            val b = a + sectors + 1
            indices += listOf(a, a + 1, b, a + 1, b + 1, b)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }
}

/** A mesh entity on a shared unit mesh: placed by centre, size and rotation. */
internal class Part(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
    }

    fun place(center: Vec3, size: Vec3, rotation: Quat4 = Quat4.IDENTITY) {
        entity.setLocal(position = center, rotation = rotation, scale = size)
        show(true)
    }

    /** A disc / square turned so that its face normal (+Z for discs, +Y for squares) points along [normal]. */
    fun placeFacing(center: Vec3, size: Vec3, normal: Vec3, meshNormal: Vec3 = Vec3(0f, 0f, 1f)) {
        place(center, size, Quat4.fromTo(meshNormal, normal.normalized()))
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun show(visible: Boolean) {
        if (visible != on) {
            entity.enabled = visible
            on = visible
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/**
 * A straight strip between two points with its face turned along a normal (made square to the strip):
 * the shared unit band scaled to length and width (bands, lines, frames, poles).
 */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
        hide()
    }

    fun draw(from: Vec3, to: Vec3, width: Float, faceNormal: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.RIGHT.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/** Four strips as a rectangle outline (frames: screen edge, absorber, snap target, gaze preview). */
internal class Frame(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val edges = List(4) { Strip(parent, mesh, material, "$name:$it") }

    /** Rectangle centred on [center], [right] x [up] half sizes, lines [width] wide, face along [normal]. */
    fun draw(center: Vec3, right: Vec3, up: Vec3, halfW: Float, halfH: Float, width: Float, normal: Vec3) {
        val a = center - right * halfW - up * halfH
        val b = center + right * halfW - up * halfH
        val c = center + right * halfW + up * halfH
        val d = center - right * halfW + up * halfH
        edges[0].draw(a, b, width, normal)
        edges[1].draw(b, c, width, normal)
        edges[2].draw(c, d, width, normal)
        edges[3].draw(d, a, width, normal)
    }

    fun material(m: Material) = edges.forEach { it.material(m) }

    fun hide() = edges.forEach { it.hide() }

    fun destroy() = edges.forEach { it.destroy() }
}

/** Thin box colliders over the detected planes (deselect taps) and a backstop shell that catches a pinch anywhere. */
internal class RoomColliders(private val root: Entity) {
    private class Collider(val entity: Entity, var center: Vec3, var size: Vec3, var rotation: Quat4)

    private val colliders = HashMap<String, Collider>()
    private val ids = HashSet<Long>()
    private var backstop: Entity? = null

    fun isRoom(entity: Entity): Boolean = entity.id in ids || runCatching { entity.getName() }.getOrNull()?.startsWith("backstop") == true

    /** The plane behind a tapped collider, or null for the backstop. */
    fun planeIdOf(entity: Entity): String? = colliders.entries.firstOrNull { it.value.entity.id == entity.id }?.key

    fun sync(planes: Collection<PlaneSurface>) {
        val seen = HashSet<String>()
        for (s in planes) {
            seen += s.id
            upsert(s)
        }
        for (id in colliders.keys.filter { it !in seen }) colliders.remove(id)?.let {
            ids.remove(it.entity.id)
            runCatching { it.entity.destroy(true) }
        }
    }

    private fun upsert(surface: PlaneSurface) {
        val fit = fit(surface) ?: return
        val existing = colliders[surface.id]
        if (existing == null) {
            val e = Entity()
            runCatching { e.setName("plane:${surface.label.replace('-', '_')}") }
            e.components.set(boxCollision(fit.second))
            e.components.set(InteractableComponent())
            root.addChild(e)
            e.setLocal(position = fit.first, rotation = fit.third)
            colliders[surface.id] = Collider(e, fit.first, fit.second, fit.third)
            ids += e.id
            return
        }
        if (existing.size.distanceTo(fit.second) > 0.001f) {
            existing.entity.components.set(boxCollision(fit.second))
            existing.size = fit.second
        }
        if (existing.center.distanceTo(fit.first) > 0.0005f || abs(existing.rotation.x - fit.third.x) + abs(existing.rotation.w - fit.third.w) > 1e-4f) {
            existing.entity.setLocal(position = fit.first, rotation = fit.third)
            existing.center = fit.first
            existing.rotation = fit.third
        }
    }

    fun buildBackstop() {
        if (backstop != null) return
        val shell = Entity()
        runCatching { shell.setName("backstop") }
        root.addChild(shell)
        val h = 9f
        val t = 0.2f
        val cy = 1.5f
        val slabs =
            listOf(
                Triple("px", Vec3(h, cy, 0f), Vec3(t, 2 * h, 2 * h)), Triple("nx", Vec3(-h, cy, 0f), Vec3(t, 2 * h, 2 * h)),
                Triple("pz", Vec3(0f, cy, h), Vec3(2 * h, 2 * h, t)), Triple("nz", Vec3(0f, cy, -h), Vec3(2 * h, 2 * h, t)),
                Triple("py", Vec3(0f, cy + h, 0f), Vec3(2 * h, t, 2 * h)), Triple("ny", Vec3(0f, cy - h, 0f), Vec3(2 * h, t, 2 * h)),
            )
        for ((tag, center, size) in slabs) {
            val slab = Entity()
            runCatching { slab.setName("backstop:$tag") }
            slab.components.set(boxCollision(size))
            slab.components.set(InteractableComponent())
            shell.addChild(slab)
            slab.setLocal(position = center)
        }
        backstop = shell
    }

    fun clear() {
        colliders.values.forEach { runCatching { it.entity.destroy(true) } }
        colliders.clear()
        ids.clear()
        runCatching { backstop?.destroy(true) }
        backstop = null
    }

    /** Oriented box around the polygon in its own plane: centre, size (6 mm thick), rotation. */
    fun fit(s: PlaneSurface): Triple<Vec3, Vec3, Quat4>? {
        val v = s.vertices
        if (v.size < 3) return null
        val n = s.normal
        var u = Vec3.ZERO
        for (i in v.indices) {
            val e = (v[(i + 1) % v.size] - v[i]).rejectFrom(n)
            if (e.length > 0.01f) {
                u = e.normalized()
                break
            }
        }
        if (u.length < 0.5f) return null
        val w = n.cross(u).normalized()
        var u0 = Float.MAX_VALUE
        var u1 = -Float.MAX_VALUE
        var w0 = Float.MAX_VALUE
        var w1 = -Float.MAX_VALUE
        for (p in v) {
            val d = p - v[0]
            u0 = minOf(u0, d.dot(u))
            u1 = maxOf(u1, d.dot(u))
            w0 = minOf(w0, d.dot(w))
            w1 = maxOf(w1, d.dot(w))
        }
        if (u1 - u0 < 0.01f || w1 - w0 < 0.01f) {
            CcLog.w("PLANES skip degenerate ${s.label}")
            return null
        }
        val center = v[0] + u * ((u0 + u1) / 2f) + w * ((w0 + w1) / 2f)
        return Triple(center, Vec3(u1 - u0, w1 - w0, 0.006f), Quat4.fromBasis(u, w, n))
    }
}

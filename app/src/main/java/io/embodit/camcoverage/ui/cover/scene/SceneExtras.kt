package io.embodit.camcoverage.ui.cover.scene

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.camcoverage.domain.Dori
import io.embodit.camcoverage.domain.Vec3
import io.embodit.camcoverage.ui.theme.CcColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Scene-lifetime materials, one per look, shared by every entity (design `palette`, `brandTokens`).
 * Unlit colours are exact only with tone mapping off (Spatial Ruler). The camera bodies are lit.
 */
internal class CcMaterials(private val shared: SharedResources) {
    val camBody: Material = lit(CcColors.CamBody, 0.6f)
    val camDark: Material = lit(CcColors.CamDark, 0.5f)
    val camStale: Material = lit(CcColors.StaleGrey, 0.7f)
    val dome: Material = sheer(CcColors.CamDark, 0.70f)
    val recOn: Material = unlit(CcColors.RecRed)
    val recIdle: Material = sheer(CcColors.RecRed, 0.40f)
    val recStale: Material = unlit(CcColors.StaleGrey)
    val coneEdge: Material = sheer(CcColors.Passable, 0.70f)
    val coneEdgeSelected: Material = unlit(CcColors.Interaction, twoSided = true)
    val coneEdgeBlocked: Material = unlit(CcColors.Error, twoSided = true)
    val coneEdgeStale: Material = sheer(CcColors.StaleGrey, 0.6f)
    val coneFill: Material = sheer(CcColors.Passable, 0.10f)
    val coneFillInside: Material = sheer(CcColors.Passable, 0.035f)
    val coneFillSelected: Material = sheer(CcColors.Interaction, 0.14f)
    val coneFillBlocked: Material = sheer(CcColors.Error, 0.14f)
    val coneFillStale: Material = sheer(CcColors.StaleGrey, 0.08f)
    val footFill: Material = sheer(CcColors.Passable, 0.12f)
    val footEdge: Material = sheer(CcColors.Passable, 0.85f)
    val footEdgeSelected: Material = sheer(CcColors.Interaction, 0.9f)
    val knob: Material = unlit(CcColors.Interaction)
    val knobRim: Material = sheer(CcColors.White, 0.30f)
    val zoneEdge: Material = sheer(CcColors.White, 0.80f)
    val zoneCorner: Material = unlit(CcColors.White, twoSided = true)
    val zoneCornerSaving: Material = sheer(CcColors.Interaction, 0.9f)
    val zoneHint: Material = sheer(CcColors.Interaction, 0.85f)
    val placeHint: Material = sheer(CcColors.Passable, 0.55f)
    val snapDash: Material = sheer(CcColors.Interaction, 0.85f)
    val ringSeen: Material = sheer(CcColors.Passable, 0.85f)
    val ringBlind: Material = sheer(CcColors.BlindGrey, 0.6f)
    val ringBlocked: Material = sheer(CcColors.Error, 0.85f)
    val ringNeutral: Material = sheer(CcColors.White, 0.40f)
    val notch: Material = unlit(CcColors.CoverInk, twoSided = true)
    val miniPlane: Material = sheer(CcColors.White, 0.20f)
    val miniBody: Material = unlit(CcColors.CamBody, twoSided = true)
    val stale: Material = sheer(CcColors.StaleGrey, 0.5f)
    val bodyOutline: Material = sheer(CcColors.Error, 0.9f)
    val white: Material = unlit(CcColors.White, twoSided = true)

    // coverage bands (design `entities3d.floorGrid` / `wallGrid` / `zoneGrid`)
    val gradeFill: Map<Dori, Material> =
        mapOf(
            Dori.IDENTIFY to sheer(CcColors.CoverDeep, 0.75f),
            Dori.RECOGNIZE to sheer(CcColors.CoverMid, 0.70f),
            Dori.OBSERVE to sheer(CcColors.CoverLight, 0.65f),
            Dori.DETECT to sheer(CcColors.CoverMid, 0.85f),
            Dori.BLIND to sheer(CcColors.BlindGrey, 0.30f),
        )
    val wallBlind: Material = sheer(CcColors.BlindGrey, 0.18f)
    val countFill: List<Material> = listOf(sheer(CcColors.BlindGrey, 0.35f), sheer(CcColors.CoverLight, 0.7f), sheer(CcColors.CoverMid, 0.75f), sheer(CcColors.CoverDeep, 0.8f))

    private fun unlit(color: Color, twoSided: Boolean = false): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** See-through: the colour is opaque, the material's opacity carries the share (Spatial Ruler). */
    private fun sheer(color: Color, opacity: Float): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    private fun lit(color: Color, roughness: Float, glow: Float = 0.25f): Material =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                setMetallic(0f)
                setRoughness(roughness)
                setEmissiveColor(Color4(color.red * glow, color.green * glow, color.blue * glow, 1f))
            }
        )
}

/** The shared meshes of the scene. */
internal class CcMeshes(shared: SharedResources) {
    val cube: MeshResource = shared.keep(MeshFactory.unitCube("unitCube"))
    val square: MeshResource = shared.keep(MeshFactory.unitSquare("unitSquare"))
    val band: MeshResource = shared.keep(MeshFactory.unitBand("unitBand"))
    val dashes: MeshResource = shared.keep(MeshFactory.unitBand("unitDashes", dashes = 10))
    val disc: MeshResource = shared.keep(MeshFactory.unitDisc("unitDisc"))
    val ring: MeshResource = shared.keep(MeshFactory.unitRing("unitRing", inner = 0.44f))
    val sphere: MeshResource = shared.keep(MeshFactory.unitSphere("unitSphere"))
    val cylinder: MeshResource = shared.keep(ExtraMeshes.unitCylinder("unitCylinder"))
}

internal object ExtraMeshes {
    /** Unit cylinder: radius 0.5, height 1 along Y, centred, with caps. */
    fun unitCylinder(name: String, segments: Int = 20): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0..segments) {
            val a = 2 * PI * i / segments
            val x = (cos(a) * 0.5).toFloat()
            val z = (sin(a) * 0.5).toFloat()
            positions += Vector3(x, -0.5f, z)
            positions += Vector3(x, 0.5f, z)
            val n = Vector3(x * 2f, 0f, z * 2f)
            normals += n
            normals += n
        }
        for (i in 0 until segments) {
            val a = i * 2
            indices += listOf(a, a + 2, a + 1, a + 1, a + 2, a + 3)
        }
        for ((y, up) in listOf(0.5f to true, -0.5f to false)) {
            val c = positions.size
            positions += Vector3(0f, y, 0f)
            normals += Vector3(0f, if (up) 1f else -1f, 0f)
            for (i in 0 until segments) {
                val a = 2 * PI * i / segments
                positions += Vector3((cos(a) * 0.5).toFloat(), y, (sin(a) * 0.5).toFloat())
                normals += Vector3(0f, if (up) 1f else -1f, 0f)
            }
            for (i in 0 until segments) {
                val b = c + 1 + i
                val d = c + 1 + (i + 1) % segments
                if (up) indices += listOf(c, d, b) else indices += listOf(c, b, d)
            }
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }
}

/** A triangle soup under construction (cones, footprints, cell grids); turned into a mesh resource at the end. */
internal class MeshBuilder {
    val positions = ArrayList<Vector3>()
    val indices = ArrayList<Int>()
    private var minX = Float.MAX_VALUE; private var minY = Float.MAX_VALUE; private var minZ = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE; private var maxY = -Float.MAX_VALUE; private var maxZ = -Float.MAX_VALUE

    val isEmpty: Boolean
        get() = indices.isEmpty()

    fun vertex(p: Vec3): Int {
        positions += Vector3(p.x, p.y, p.z)
        minX = min(minX, p.x); minY = min(minY, p.y); minZ = min(minZ, p.z)
        maxX = max(maxX, p.x); maxY = max(maxY, p.y); maxZ = max(maxZ, p.z)
        return positions.size - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        indices += a
        indices += b
        indices += c
    }

    fun triangle(a: Vec3, b: Vec3, c: Vec3) = tri(vertex(a), vertex(b), vertex(c))

    fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3) {
        val i = vertex(a)
        val j = vertex(b)
        val k = vertex(c)
        val l = vertex(d)
        tri(i, j, k)
        tri(i, k, l)
    }

    /** A rectangle centred on [c], [right] x [up] half sizes. */
    fun rect(c: Vec3, right: Vec3, up: Vec3, hw: Float, hh: Float) = quad(c - right * hw - up * hh, c + right * hw - up * hh, c + right * hw + up * hh, c - right * hw + up * hh)

    /** A rectangle outline of line width [w]. */
    fun outline(c: Vec3, right: Vec3, up: Vec3, hw: Float, hh: Float, w: Float) {
        rect(c - up * (hh - w / 2f), right, up, hw, w / 2f)
        rect(c + up * (hh - w / 2f), right, up, hw, w / 2f)
        rect(c - right * (hw - w / 2f), right, up, w / 2f, hh - w)
        rect(c + right * (hw - w / 2f), right, up, w / 2f, hh - w)
    }

    fun build(name: String): MeshResource? {
        if (isEmpty) return null
        val center = Vector3((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
        val half = Vector3(max(0.001f, (maxX - minX) / 2f), max(0.001f, (maxY - minY) / 2f), max(0.001f, (maxZ - minZ) / 2f))
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { Vector3(0f, 1f, 0f) })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = center, halfExtent = half), name = name)
    }
}

/**
 * A mesh that is rebuilt from scratch when its data changes (cone volumes, footprints, cell bands).
 * The same mesh resource is shown under every host parent (the room and the mini room share one mesh,
 * design `entities3d.miniRoom`); the old mesh is closed once the new entities are up.
 */
internal class DynMesh(private val hosts: List<Entity>, private var material: Material, private val name: String) {
    private var mesh: MeshResource? = null
    private val entities = ArrayList<Entity>()
    private var on = true

    val isShown: Boolean
        get() = mesh != null && on

    fun set(builder: MeshBuilder?) {
        val next = builder?.build(name)
        clearEntities()
        val old = mesh
        mesh = next
        if (next != null) {
            next.toGlobal()
            for (h in hosts) {
                val e = ModelEntity(next, material)
                runCatching { e.setName(name) }
                h.addChild(e)
                e.enabled = on
                entities += e
            }
        }
        old?.let { runCatching { it.close() } }
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entities.forEach { it.setMaterial(m) }
    }

    fun show(visible: Boolean) {
        on = visible
        entities.forEach { it.enabled = visible }
    }

    private fun clearEntities() {
        entities.forEach { runCatching { it.destroy(true) } }
        entities.clear()
    }

    fun destroy() {
        clearEntities()
        mesh?.let { runCatching { it.close() } }
        mesh = null
    }
}

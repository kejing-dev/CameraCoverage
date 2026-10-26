package io.embodit.camcoverage.domain

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");
}

enum class LengthUnit(val tag: String) {
    M("m"),
    FT("ft");

    companion object {
        fun defaultFor(country: String): LengthUnit = if (country.uppercase(Locale.US) == "US") FT else M
    }
}

/** Preferences kept apart from the layout (design `persistence.what`). */
data class Settings(
    val language: LanguagePref = LanguagePref.SYSTEM,
    val unit: LengthUnit = LengthUnit.M,
    val lastMode: Mode = Mode.WORKCELL,
    val coachSeenWork: Boolean = false,
    val coachSeenHome: Boolean = false,
) {
    fun coachSeen(mode: Mode): Boolean = if (mode == Mode.WORKCELL) coachSeenWork else coachSeenHome

    fun withCoachSeen(mode: Mode): Settings = if (mode == Mode.WORKCELL) copy(coachSeenWork = true) else copy(coachSeenHome = true)
}

/** A world anchor's pose as the app sees it. */
data class AnchorPose(val position: Vec3, val rotation: Quat4) {
    fun toLocal(world: Vec3): Vec3 = rotation.conjugate().rotate(world - position)

    fun toWorld(local: Vec3): Vec3 = rotation.rotate(local) + position

    fun toLocalDir(d: Vec3): Vec3 = rotation.conjugate().rotate(d).normalized()

    fun toWorldDir(d: Vec3): Vec3 = rotation.rotate(d).normalized()
}

/** Both layouts relative to the room anchor (design `persistence`). */
data class SavedLayout(val anchorId: String, val layouts: Map<Mode, Layout>)

object LayoutCodec {
    fun encode(l: SavedLayout, a: AnchorPose): String {
        val o = JSONObject()
        o.put("anchor", l.anchorId)
        val modes = JSONObject()
        for ((mode, layout) in l.layouts) {
            val m = JSONObject()
            val cams = JSONArray()
            for (c in layout.cameras) {
                cams.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("mount", c.mount.tag)
                        .put("p", vec(a.toLocal(c.mountPoint)))
                        .put("n", vec(a.toLocalDir(c.normal)))
                        .put("d", vec(a.toLocalDir(c.dir)))
                        .put("h", c.height.toDouble())
                        .put("floor", c.onFloor)
                        .put("lens", c.lens.tag)
                        .put("res", c.res.tag)
                        .put("type", c.type.tag)
                        .put("surface", c.surface)
                )
            }
            m.put("cameras", cams)
            layout.zone?.let { z ->
                val along = a.toLocalDir(z.along.at(0f))
                m.put("zone", JSONObject().put("c", vec(a.toLocal(z.center))).put("along", JSONArray().put(along.x.toDouble()).put(along.z.toDouble())).put("size", z.size.tag).put("h", z.height.tag))
            }
            modes.put(mode.tag, m)
        }
        o.put("modes", modes)
        return o.toString()
    }

    /** Decodes with the anchor's pose at load time ([a] null: the raw local values are kept in a unit anchor). */
    fun decode(text: String?, a: AnchorPose?): SavedLayout? {
        if (text.isNullOrBlank()) return null
        val pose = a ?: AnchorPose(Vec3.ZERO, Quat4.IDENTITY)
        return runCatching {
                val o = JSONObject(text)
                val modes = o.getJSONObject("modes")
                val out = LinkedHashMap<Mode, Layout>()
                for (mode in Mode.entries) {
                    val m = modes.optJSONObject(mode.tag) ?: continue
                    val cams = ArrayList<Camera>()
                    val arr = m.optJSONArray("cameras") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONObject(i)
                        cams +=
                            Camera(
                                id = c.getInt("id"),
                                mount = Mount.byTag(c.optString("mount")),
                                mountPoint = pose.toWorld(vec(c.getJSONArray("p"))),
                                normal = pose.toWorldDir(vec(c.getJSONArray("n"))),
                                dir = pose.toWorldDir(vec(c.getJSONArray("d"))),
                                height = c.optDouble("h", 0.0).toFloat(),
                                onFloor = c.optBoolean("floor", false),
                                lens = Lens.byTag(c.optString("lens")),
                                res = Resolution.byTag(c.optString("res")),
                                type = CamType.byTag(c.optString("type")),
                                surface = c.optString("surface", ""),
                            )
                    }
                    val z = m.optJSONObject("zone")
                    val zone =
                        z?.let {
                            val al = it.getJSONArray("along")
                            val d = pose.toWorldDir(Vec3(al.getDouble(0).toFloat(), 0f, al.getDouble(1).toFloat()))
                            Zone(center = pose.toWorld(vec(it.getJSONArray("c"))), along = P2(d.x, d.z).normalized(), size = ZoneSize.byTag(it.optString("size")), height = ZoneHeight.byTag(it.optString("h")))
                        }
                    out[mode] = Layout(cams, zone)
                }
                SavedLayout(o.getString("anchor"), out)
            }
            .getOrNull()
    }

    fun anchorIdOf(text: String?): String? = if (text.isNullOrBlank()) null else runCatching { JSONObject(text).getString("anchor") }.getOrNull()

    private fun vec(v: Vec3) = JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.z.toDouble())

    private fun vec(a: JSONArray) = Vec3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
}

/** Units for the panels (design `rules.units`). */
object Units {
    fun distance(m: Float, unit: LengthUnit): String = if (unit == LengthUnit.M) String.format(Locale.US, "%.2f m", m) else String.format(Locale.US, "%.1f ft", m / 0.3048f)

    /** "7.0 m" / "14 m" style range (one decimal under 10 m). */
    fun range(m: Float, unit: LengthUnit): String {
        val v = if (unit == LengthUnit.M) m else m / 0.3048f
        val u = if (unit == LengthUnit.M) "m" else "ft"
        return if (v < 10f) String.format(Locale.US, "%.1f %s", v, u) else String.format(Locale.US, "%d %s", Math.round(v), u)
    }

    /** Pixel density: px/m or PPF. */
    fun density(pxPerM: Float, unit: LengthUnit): String =
        if (unit == LengthUnit.M) "${Optics.floor5(pxPerM)} px/m" else "${Optics.floor5(pxPerM / 3.281f)} PPF"

    /** Workcell clarity: px/cm or px/in. */
    fun clarity(pxPerCm: Float, unit: LengthUnit): String =
        if (unit == LengthUnit.M) String.format(Locale.US, "%d px/cm", Math.round(pxPerCm)) else String.format(Locale.US, "%d px/in", Math.round(pxPerCm * 2.54f))
}

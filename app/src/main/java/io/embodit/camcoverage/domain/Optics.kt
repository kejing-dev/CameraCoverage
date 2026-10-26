package io.embodit.camcoverage.domain

import kotlin.math.atan
import kotlin.math.tan

/** Home-mode lens presets (design `modes.home.presets`): focal length on a 1/2.7" 16:9 sensor. */
enum class Lens(val mm: Float, val tag: String) {
    L28(2.8f, "2.8"),
    L4(4f, "4"),
    L6(6f, "6"),
    L8(8f, "8"),
    L12(12f, "12");

    /** Horizontal / vertical field of view in degrees (pinhole estimate, `modes.home.fov.formula`). */
    val hfovDeg: Float
        get() = Math.toDegrees(2.0 * atan(Optics.SENSOR_W_MM / (2.0 * mm))).toFloat()

    val vfovDeg: Float
        get() = Math.toDegrees(2.0 * atan(Optics.SENSOR_H_MM / (2.0 * mm))).toFloat()

    companion object {
        fun byIndex(i: Int): Lens = entries.getOrElse(i) { L4 }

        fun byTag(tag: String?): Lens = entries.firstOrNull { it.tag == tag } ?: L4
    }
}

/** Home-mode resolution presets: horizontal pixels. */
enum class Resolution(val px: Int, val tag: String) {
    MP2(1920, "2"),
    MP4(2560, "4"),
    MP8(3840, "8");

    companion object {
        fun byIndex(i: Int): Resolution = entries.getOrElse(i) { MP4 }

        fun byTag(tag: String?): Resolution = entries.firstOrNull { it.tag == tag } ?: MP4
    }
}

/** Workcell camera types (design `modes.workcell.presets`); the depth type has an effective range instead of px/cm. */
enum class CamType(val hfovDeg: Float, val vfovDeg: Float, val px: Int, val depth: Boolean, val tag: String) {
    WIDE(90f, 59f, 1920, false, "wide"),
    STANDARD(70f, 43f, 1920, false, "standard"),
    DEPTH(87f, 58f, 1280, true, "depth"),
    TELE(40f, 23f, 1920, false, "tele");

    companion object {
        fun byIndex(i: Int): CamType = entries.getOrElse(i) { STANDARD }

        fun byTag(tag: String?): CamType = entries.firstOrNull { it.tag == tag } ?: STANDARD
    }
}

/** DORI grades (IEC EN 62676-4) plus "blind" for nothing or under 25 px/m. Order = best first. */
enum class Dori(val minPxPerM: Float) {
    IDENTIFY(250f),
    RECOGNIZE(125f),
    OBSERVE(62.5f),
    DETECT(25f),
    BLIND(0f);

    val seen: Boolean
        get() = this != BLIND
}

/** The pinhole maths of the design (`rules.pixelDensity`, `rules.dori`, `modes.home.fov`). */
object Optics {
    const val SENSOR_W_MM = 5.86
    const val SENSOR_H_MM = 3.30

    /** Pixels per metre at depth [depthM] along the optical axis for [px] horizontal pixels over [hfovDeg]. */
    fun pxPerM(px: Int, hfovDeg: Float, depthM: Float): Float {
        if (depthM <= 1e-3f) return Float.MAX_VALUE
        val w = 2f * depthM * tan(Math.toRadians(hfovDeg / 2.0)).toFloat()
        return px / w
    }

    fun dori(pxPerM: Float): Dori = Dori.entries.first { pxPerM >= it.minPxPerM }

    /** The farthest depth at which [grade] still holds (design `modes.home.cardRange`). */
    fun rangeFor(grade: Dori, px: Int, hfovDeg: Float): Float = px / (2f * tan(Math.toRadians(hfovDeg / 2.0)).toFloat() * grade.minPxPerM)

    /** Shown values are floored to 5 px/m so that 123 never reads as the next grade (`rules.dori`). */
    fun floor5(pxPerM: Float): Int = ((pxPerM / 5f).toInt() * 5)

    /** Workcell clarity: px/cm thresholds (design `modes.workcell.countRule`). */
    const val CLARITY_OK_PX_CM = 8f
    const val CLARITY_MIN_PX_CM = 4f
    const val DEPTH_MIN_M = 0.3f
    const val DEPTH_MAX_M = 1.5f

    /** Longest ray of a cone (design: home 15 m, workcell 3 m). */
    fun maxRange(mode: Mode): Float = if (mode == Mode.HOME) 15f else 3f
}

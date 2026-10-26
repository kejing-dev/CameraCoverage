package io.embodit.camcoverage.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import io.embodit.camcoverage.domain.Dori

/**
 * The design's exact colours (design-package `palette`): night-vision green + REC red. This is the only
 * file with colour literals; the four semantic roles go into `PicoTheme`, the brand tokens are used
 * directly (3D materials, the solid dark faces of tags).
 */
object CcColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Error = Color(0xFFFF5A4E) // design-style: fixed-figma-color error: blocked by you
    val Alert = Color(0xFFFFB020) // design-style: fixed-figma-color alert: low clarity / too close
    val Passable = Color(0xFF3BE38A) // design-style: fixed-figma-color passable: seen, cone edges
    val Interaction = Color(0xFFFFD84A) // design-style: fixed-figma-color interaction: selected, aim knob

    // ---- brand tokens outside ColorScheme
    val CoverDeep = Color(0xFF0E9F5B) // design-style: fixed-figma-color coverDeep: identify / 3+ cams
    val CoverMid = Color(0xFF2FD27A) // design-style: fixed-figma-color coverMid: recognize / 2 cams
    val CoverLight = Color(0xFF8FF0B6) // design-style: fixed-figma-color coverLight: observe / 1 cam
    val BlindGrey = Color(0xFF8C918E) // design-style: fixed-figma-color blindGrey
    val RecRed = Color(0xFFFF3B30) // design-style: fixed-figma-color recRed
    val CoverInk = Color(0xFF0B1A13) // design-style: fixed-figma-color coverInk: text on semantic fills, tag faces
    val CamBody = Color(0xFFE9ECEA) // design-style: fixed-figma-color camBody
    val CamDark = Color(0xFF2A2F2C) // design-style: fixed-figma-color camDark
    val StaleGrey = Color(0xFF9A9A96) // design-style: fixed-figma-color staleGrey: tracking lost
    val IconBg = Color(0xFF0B4A34) // design-style: fixed-figma-color iconBg: app icon only
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val OnInkFill = Color(0x24FFFFFF) // design-style: fixed-figma-color onInkFill white 14 %
    val OnInkSecondary = Color(0xA8FFFFFF) // design-style: fixed-figma-color onInkSecondary white 66 %

    /** The grade colour of a cell (design `palette.semantic`): detect uses coverMid as an outline, blind is grey. */
    fun ofDori(d: Dori): Color =
        when (d) {
            Dori.IDENTIFY -> CoverDeep
            Dori.RECOGNIZE -> CoverMid
            Dori.OBSERVE -> CoverLight
            Dori.DETECT -> CoverMid
            Dori.BLIND -> BlindGrey
        }

    fun ofCount(n: Int): Color =
        when {
            n >= 3 -> CoverDeep
            n == 2 -> CoverMid
            n == 1 -> CoverLight
            else -> BlindGrey
        }

    @Composable
    fun passable(): Color = PicoTheme.colorScheme.passable
}

/** Type roles of the design (`typography.roles`): tabular figures everywhere a number is shown. */
object CcType {
    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyLargeMultiline.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)

    val dialogTitle: TextStyle
        @Composable get() = PicoTheme.typography.headlineLarge.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
}

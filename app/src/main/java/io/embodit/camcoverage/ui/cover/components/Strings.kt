package io.embodit.camcoverage.ui.cover.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.camcoverage.R
import io.embodit.camcoverage.domain.CamType
import io.embodit.camcoverage.domain.Dori
import io.embodit.camcoverage.domain.LanguagePref
import io.embodit.camcoverage.domain.Mode
import io.embodit.camcoverage.domain.Mount
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中文 / EN), switched at run time without
 * restarting the Stage: the screen provides a Resources object for that locale and every panel reads
 * its strings from it (Spatial Ruler). `LocalContext` is not overridden.
 */
val LocalCcResources = staticCompositionLocalOf<Resources> { error("LocalCcResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalCcResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalCcResources.current.getString(id, *args)

@Composable
@ReadOnlyComposable
fun modeName(mode: Mode): String = rs(if (mode == Mode.WORKCELL) R.string.mode_workcell else R.string.mode_home)

@Composable
@ReadOnlyComposable
fun mountName(mount: Mount): String = rs(when (mount) { Mount.WALL -> R.string.mount_wall; Mount.CEILING -> R.string.mount_ceiling; Mount.TRIPOD -> R.string.mount_tripod })

@Composable
@ReadOnlyComposable
fun typeName(t: CamType): String = rs(when (t) { CamType.WIDE -> R.string.type_wide; CamType.STANDARD -> R.string.type_standard; CamType.DEPTH -> R.string.type_depth; CamType.TELE -> R.string.type_tele })

@Composable
@ReadOnlyComposable
fun doriName(d: Dori): String = rs(when (d) { Dori.IDENTIFY -> R.string.dori_identify; Dori.RECOGNIZE -> R.string.dori_recognize; Dori.OBSERVE -> R.string.dori_observe; Dori.DETECT -> R.string.dori_detect; Dori.BLIND -> R.string.dori_blind })

/** Panel sizes (design-package `panels[].sizeDp`), in dp. */
object PanelSizes {
    const val CONSOLE_W = 400f
    const val CONSOLE_H = 208f
    const val CARD_W = 400f
    const val CARD_H = 344f
    const val ZONE_W = 360f
    const val ZONE_H = 176f
    const val MENU_W = 272f
    const val MENU_H = 200f
    const val DIALOG_W = 400f
    const val DIALOG_H = 236f
    const val SNACK_W = 560f
    const val SNACK_H = 56f
    const val COACH_W = 360f
    const val COACH_H = 88f
    const val TAG_W = 220f
    const val TAG_H = 36f

    /** Disabled content (SpatialUI's own disabled alpha). */
    const val DISABLED_ALPHA = 0.38f
}

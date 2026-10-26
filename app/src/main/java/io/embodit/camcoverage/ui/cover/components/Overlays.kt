package io.embodit.camcoverage.ui.cover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.camcoverage.R
import io.embodit.camcoverage.domain.LanguagePref
import io.embodit.camcoverage.domain.LengthUnit
import io.embodit.camcoverage.domain.Mode
import io.embodit.camcoverage.ui.cover.CoverEvent
import io.embodit.camcoverage.ui.cover.CoverUiState
import io.embodit.camcoverage.ui.cover.SnackKind
import io.embodit.camcoverage.ui.cover.SnackUi
import io.embodit.camcoverage.ui.cover.debug.probe
import io.embodit.camcoverage.ui.theme.CcColors
import io.embodit.camcoverage.ui.theme.CcType

/** The More menu (design `panels.moreMenu`, 272 x 200): language and unit segments that act in place, Clear all (this mode), Exit. */
@Composable
internal fun MoreMenu(state: CoverUiState?, onEvent: (CoverEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.MENU_W.dp, PanelSizes.MENU_H.dp)
                .clip(RoundedCornerShape(24.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == null) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        Segments(items = LanguagePref.entries, selected = state.settings.language, width = (PanelSizes.MENU_W - 16f).dp, enabled = true, name = { "lang_${it.tag}" }, onSelect = { onEvent(CoverEvent.LanguageSelected(it)) }) { item ->
            when (item) {
                LanguagePref.SYSTEM -> Icon(painter = painterResource(R.drawable.ic_globe), contentDescription = rs(R.string.lang_system), modifier = Modifier.size(20.dp), tint = ink)
                LanguagePref.ZH -> Text(text = rs(R.string.lang_zh), color = ink, style = CcType.label, maxLines = 1, softWrap = false)
                LanguagePref.EN -> {
                    val english = rs(R.string.lang_en)
                    Text(text = rs(R.string.lang_en_short), color = ink, style = CcType.label, maxLines = 1, softWrap = false, modifier = Modifier.semantics { contentDescription = english })
                }
            }
        }
        Segments(items = LengthUnit.entries, selected = state.unit, width = (PanelSizes.MENU_W - 16f).dp, enabled = true, name = { "unit_${it.tag}" }, onSelect = { onEvent(CoverEvent.UnitSelected(it)) }) { item ->
            Text(text = rs(if (item == LengthUnit.M) R.string.unit_m else R.string.unit_ft), color = ink, style = CcType.label, maxLines = 1)
        }
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canClear = state.cameras.isNotEmpty() || state.zone != null
            val clearInk = if (canClear) ink else PicoTheme.colorScheme.labelQuaternary
            WordButton("menu_clear", R.drawable.ic_trash, rs(R.string.menu_clear), PicoTheme.colorScheme.fillTertiary, clearInk, enabled = canClear, modifier = Modifier.weight(1f)) { onEvent(CoverEvent.ClearAllClicked) }
            Key(name = "menu_exit", icon = R.drawable.ic_exit, description = rs(R.string.menu_exit), enabled = true) { onEvent(CoverEvent.ExitClicked) }
        }
    }
}

/**
 * The two dialogs (design `panels.clearDialog` / `blockedDialog`, 400 wide): the clear confirmation
 * (camera icons x n + mode name) and "spatial features unavailable". [state.blocked] wins.
 */
@Composable
internal fun DialogPanel(state: CoverUiState?, onEvent: (CoverEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.DIALOG_W.dp, PanelSizes.DIALOG_H.dp)
                .clip(RoundedCornerShape(32.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == null || !(state.clearDialog || state.blocked)) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        val blocked = state.blocked
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painter = painterResource(if (blocked) R.drawable.ic_unavailable else R.drawable.ic_trash), contentDescription = null, modifier = Modifier.size(32.dp), tint = ink)
            if (blocked) {
                Text(text = rs(R.string.dialog_unavailable), color = ink, style = CcType.dialogTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("dialog_title"))
            } else {
                val n = if (state.clearBoth) state.layouts.values.sumOf { it.cameras.size } else state.cameras.size
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    repeat(n.coerceAtMost(4)) { Icon(painter = painterResource(R.drawable.ic_add_camera), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink) }
                    Text(text = if (state.clearBoth) rs(R.string.dialog_clear_both_title, n) else rs(R.string.dialog_clear_title, n, modeName(state.mode)), color = ink, style = CcType.dialogTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("dialog_title"))
                }
            }
        }
        Text(
            text = rs(when { blocked -> R.string.dialog_unavailable_body; state.clearBoth -> R.string.dialog_clear_both; else -> R.string.dialog_clear_body }),
            color = PicoTheme.colorScheme.labelSecondary,
            style = CcType.body,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.probe("dialog_body"),
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (blocked) {
                WordButton("blocked_exit", R.drawable.ic_exit, rs(R.string.action_exit), PicoTheme.colorScheme.fillTertiary, ink) { onEvent(CoverEvent.BlockedExit) }
                WordButton("blocked_retry", R.drawable.ic_retry, rs(R.string.action_retry), PicoTheme.colorScheme.interaction, CcColors.CoverInk) { onEvent(CoverEvent.BlockedRetry) }
            } else {
                WordButton("dialog_cancel", R.drawable.ic_close, rs(R.string.action_cancel), PicoTheme.colorScheme.fillTertiary, ink) { onEvent(CoverEvent.DialogDismissed) }
                WordButton("dialog_confirm", R.drawable.ic_trash, rs(R.string.action_clear), PicoTheme.colorScheme.error, CcColors.CoverInk) { onEvent(CoverEvent.ClearConfirmed) }
            }
        }
    }
}

/** The snackbar (design `panels.snackbar`): Thick glass capsule, icon + one short sentence + optional action. */
@Composable
internal fun SnackPanel(snack: SnackUi?, onEvent: (CoverEvent) -> Unit) {
    Box(modifier = Modifier.size(PanelSizes.SNACK_W.dp, PanelSizes.SNACK_H.dp), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val ink = PicoTheme.colorScheme.labelPrimary
        val icon: Int
        val text: String
        val action: Pair<Int, Int>?
        when (snack.kind) {
            SnackKind.SCANNING -> { icon = 0; text = rs(R.string.snack_scanning); action = null }
            SnackKind.LIMIT -> { icon = R.drawable.ic_warning; text = rs(R.string.snack_limit); action = null }
            SnackKind.RESTORED -> { icon = R.drawable.ic_check; text = rs(R.string.snack_restored, snack.count); action = null }
            SnackKind.ELSEWHERE -> { icon = R.drawable.ic_warning; text = rs(R.string.snack_elsewhere); action = R.string.action_clear to R.drawable.ic_trash }
            SnackKind.UNSAVED -> { icon = R.drawable.ic_warning; text = rs(R.string.snack_unsaved); action = R.string.action_retry to R.drawable.ic_retry }
            SnackKind.LOST -> { icon = R.drawable.ic_unavailable; text = rs(R.string.snack_lost); action = null }
            SnackKind.CLEARED -> { icon = R.drawable.ic_check; text = rs(R.string.snack_cleared); action = null }
            SnackKind.IN_ZONE -> { icon = R.drawable.ic_target; text = rs(R.string.snack_in_zone); action = null }
        }
        val tint = if (snack.kind == SnackKind.LIMIT || snack.kind == SnackKind.UNSAVED || snack.kind == SnackKind.ELSEWHERE) PicoTheme.colorScheme.alert else ink
        Row(
            modifier =
                Modifier
                    .widthIn(min = 240.dp, max = PanelSizes.SNACK_W.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .then(glassRefresh())
                    .probe("snackbar")
                    .padding(start = 16.dp, end = if (action != null) 8.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon == 0) Spinner(20.dp, ink) else Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
            Text(text = text, color = ink, style = CcType.title, maxLines = 1, softWrap = false)
            if (action != null) {
                Button(
                    onClick = { onEvent(CoverEvent.SnackActionClicked) },
                    modifier = Modifier.probe("snackbar_action"),
                    size = ButtonDefaults.Regular.copy(height = 40.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = CcColors.CoverInk),
                ) {
                    Icon(painter = painterResource(action.second), contentDescription = null, modifier = Modifier.size(18.dp), tint = CcColors.CoverInk)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(text = rs(action.first), color = CcColors.CoverInk, maxLines = 1)
                }
            }
        }
    }
}

/** The one-step coachmark (design `panels.coachmark`, 360 x 88): pictogram, one sentence, tick. */
@Composable
internal fun CoachPanel(state: CoverUiState?, onEvent: (CoverEvent) -> Unit) {
    Row(
        modifier =
            Modifier
                .size(PanelSizes.COACH_W.dp, PanelSizes.COACH_H.dp)
                .clip(RoundedCornerShape(24.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == null) return@Row
        val ink = PicoTheme.colorScheme.labelPrimary
        val work = state.mode == Mode.WORKCELL
        Icon(painter = painterResource(if (work) R.drawable.ic_mode_workcell else R.drawable.ic_mode_home), contentDescription = null, modifier = Modifier.size(36.dp), tint = ink)
        Text(text = rs(if (work) R.string.coach_workcell else R.string.coach_home), color = ink, style = CcType.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("coach_text"))
        IconButton(
            onClick = { onEvent(CoverEvent.CoachDismissed) },
            modifier = Modifier.probe("coach_ok"),
            colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = CcColors.CoverInk),
            size = IconButtonDefaults.iconButtonSize(width = 48.dp, height = 48.dp),
        ) { Icon(painter = painterResource(R.drawable.ic_check), contentDescription = rs(R.string.coach_ok), modifier = Modifier.size(24.dp), tint = CcColors.CoverInk) }
    }
}

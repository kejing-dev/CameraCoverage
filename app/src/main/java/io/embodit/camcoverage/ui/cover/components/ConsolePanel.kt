package io.embodit.camcoverage.ui.cover.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.camcoverage.R
import io.embodit.camcoverage.domain.Mode
import io.embodit.camcoverage.domain.Units
import io.embodit.camcoverage.ui.cover.CoverEvent
import io.embodit.camcoverage.ui.cover.CoverUiState
import io.embodit.camcoverage.ui.cover.Phase
import io.embodit.camcoverage.ui.cover.RestoreState
import io.embodit.camcoverage.ui.cover.debug.probe
import io.embodit.camcoverage.ui.theme.CcColors
import io.embodit.camcoverage.ui.theme.CcType
import kotlin.math.roundToInt

internal val RowH = 48.dp
internal val KeyW = 56.dp

/**
 * The console (design `panels.console`, 400 x 208 dp at 0.75): the one resident panel. Row 1 is the
 * mode segment and the More key, row 2 the one-sentence verdict, row 3 [Add camera] and Undo.
 */
@Composable
internal fun ConsolePanel(state: CoverUiState?, onEvent: (CoverEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.CONSOLE_W.dp, PanelSizes.CONSOLE_H.dp)
                .clip(RoundedCornerShape(28.dp))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == null) return@Column
        val enabled = state.consoleEnabled
        val ink = PicoTheme.colorScheme.labelPrimary
        // row 1: [workcell | home] · more
        Row(modifier = Modifier.fillMaxWidth().height(RowH).alpha(if (enabled) 1f else PanelSizes.DISABLED_ALPHA), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Segments(
                items = Mode.entries,
                selected = state.mode,
                width = 232.dp,
                enabled = enabled,
                name = { "mode_${it.tag}" },
                onSelect = { onEvent(CoverEvent.ModeSelected(it)) },
            ) { mode ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painter = painterResource(if (mode == Mode.WORKCELL) R.drawable.ic_mode_workcell else R.drawable.ic_mode_home), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
                    Text(text = modeName(mode), color = ink, style = CcType.label, maxLines = 1, softWrap = false)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Key(name = "more", icon = R.drawable.ic_more, description = rs(R.string.action_more), enabled = enabled) { onEvent(CoverEvent.MoreClicked) }
        }
        // row 2: the verdict line
        StatusRow(state)
        // row 3: [add camera] · undo
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val canAdd = state.canAdd
            val placing = state.placing
            val container = when { !canAdd -> PicoTheme.colorScheme.fillTertiary; placing -> PicoTheme.colorScheme.interaction; else -> PicoTheme.colorScheme.fillPrimary }
            val content = when { !canAdd -> PicoTheme.colorScheme.labelQuaternary; placing -> CcColors.CoverInk; else -> PicoTheme.colorScheme.labelPrimaryLight }
            Button(
                onClick = { onEvent(CoverEvent.AddCameraClicked) },
                modifier = Modifier.weight(1f).probe("add_camera"),
                size = ButtonDefaults.Regular.copy(height = RowH),
                shape = CircleShape,
                enabled = canAdd,
                colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
            ) {
                Icon(painter = painterResource(if (placing) R.drawable.ic_close else R.drawable.ic_add_camera), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = rs(when { placing -> R.string.placing; state.full -> R.string.full; else -> R.string.add_camera }), color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Key(name = "undo", icon = R.drawable.ic_undo, description = rs(R.string.action_undo), enabled = enabled && state.canUndo) { onEvent(CoverEvent.UndoClicked) }
        }
    }
}

private data class Line(val text: String, val bg: Color, val fg: Color, val icon: Int?, val spinner: Boolean = false)

/** The 56 dp verdict row (design `spatialUiMap.statusLine`): rounded block whose colour follows the verdict, icon, one sentence. */
@Composable
private fun StatusRow(state: CoverUiState) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val cov = state.coverage?.takeIf { it.mode == state.mode }
    val pct = { v: Float -> (v * 100f).roundToInt() }
    val neutral = PicoTheme.colorScheme.fillTertiary
    val line: Line =
        when {
            state.phase == Phase.STARTING -> Line("", neutral, ink, null, spinner = true)
            state.phase == Phase.SCANNING -> Line(rs(R.string.snack_scanning), neutral, ink, null, spinner = true)
            state.restore == RestoreState.LOOKING -> Line(rs(R.string.status_restoring), neutral, ink, null, spinner = true)
            state.restore == RestoreState.ELSEWHERE -> Line(rs(R.string.snack_elsewhere), neutral, ink, R.drawable.ic_warning)
            state.trackingLost -> Line(rs(R.string.snack_lost), CcColors.StaleGrey, CcColors.CoverInk, R.drawable.ic_unavailable)
            state.roomPending -> Line(rs(R.string.status_room_pending), neutral, ink, null, spinner = true)
            state.mode == Mode.WORKCELL && state.zone == null -> Line(rs(if (state.room.tables.isEmpty()) R.string.status_tap_floor else R.string.status_tap_table), PicoTheme.colorScheme.interaction, CcColors.CoverInk, R.drawable.ic_target)
            state.mode == Mode.WORKCELL && state.cameras.isEmpty() -> Line(rs(R.string.status_zone_empty), neutral, ink, R.drawable.ic_add_camera)
            state.mode == Mode.WORKCELL -> {
                val two = cov?.let { pct(it.twoPlusShare) } ?: 0
                if (state.blockedCams.isNotEmpty()) Line(rs(R.string.status_workcell_blocked, two, state.blockedCams.size), PicoTheme.colorScheme.error, CcColors.CoverInk, R.drawable.ic_block)
                else Line(rs(R.string.status_workcell, two), if (two >= 50) CcColors.CoverMid else PicoTheme.colorScheme.alert, CcColors.CoverInk, R.drawable.ic_eye)
            }
            state.cameras.isEmpty() -> Line(rs(R.string.status_home_blind, 100), CcColors.BlindGrey, CcColors.CoverInk, R.drawable.ic_eye_off)
            else -> {
                val blind = cov?.let { pct(it.blindShare) } ?: 0
                if (state.you.seen) Line(rs(R.string.status_home_seen, blind, doriName(state.you.grade), Units.density(state.you.pxPerM, state.unit)), PicoTheme.colorScheme.passable, CcColors.CoverInk, R.drawable.ic_eye)
                else Line(rs(R.string.status_home_blind, blind), CcColors.BlindGrey, CcColors.CoverInk, R.drawable.ic_eye_off)
            }
        }
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(line.bg).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (line.spinner) CircularProgressIndicator(modifier = Modifier.size(22.dp), color = line.fg, progressSize = CircularProgressDefaults.circleProgressSize(22.dp))
        else if (line.icon != null) Icon(painter = painterResource(line.icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = line.fg)
        Text(text = line.text, color = line.fg, style = CcType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("status"))
    }
}

@Composable
internal fun Key(name: String, icon: Int, description: String, enabled: Boolean, width: Dp = KeyW, container: Color? = null, content: Color? = null, onClick: () -> Unit) {
    val ink = content ?: if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor = container ?: PicoTheme.colorScheme.fillTertiary, contentColor = ink),
        size = IconButtonDefaults.iconButtonSize(width = width, height = RowH),
    ) { Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp), tint = ink) }
}

@Composable
internal fun <T> Segments(items: List<T>, selected: T, width: Dp, enabled: Boolean, name: (T) -> String, onSelect: (T) -> Unit, title: @Composable (T) -> Unit) {
    // the control keeps about 4 dp of its own padding on each side (Furniture Fit's menu measurement)
    val itemW = (width - 8.dp) / items.size
    SegmentControl(modifier = Modifier.width(width).height(RowH), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEach { item ->
            SegmentItem(
                selected = item == selected,
                onClick = { if (enabled) onSelect(item) },
                modifier = Modifier.width(itemW).fillMaxHeight().probe(name(item)),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = { title(item) },
            )
        }
    }
}

/** A capsule button with an icon and a word (cards, dialogs). */
@Composable
internal fun WordButton(name: String, icon: Int?, text: String, container: Color, content: Color, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.probe(name),
        size = ButtonDefaults.Regular.copy(height = RowH, minWidth = 96.dp),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = text, color = content, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun Spinner(size: Dp, color: Color) {
    Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(size), color = color, progressSize = CircularProgressDefaults.circleProgressSize(size)) }
}

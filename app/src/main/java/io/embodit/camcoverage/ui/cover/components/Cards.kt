package io.embodit.camcoverage.ui.cover.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.camcoverage.R
import io.embodit.camcoverage.domain.CamType
import io.embodit.camcoverage.domain.Camera
import io.embodit.camcoverage.domain.Dori
import io.embodit.camcoverage.domain.Lens
import io.embodit.camcoverage.domain.Mode
import io.embodit.camcoverage.domain.Mount
import io.embodit.camcoverage.domain.Optics
import io.embodit.camcoverage.domain.Resolution
import io.embodit.camcoverage.domain.Units
import io.embodit.camcoverage.domain.Vec3
import io.embodit.camcoverage.domain.ZoneHeight
import io.embodit.camcoverage.domain.ZoneSize
import io.embodit.camcoverage.ui.cover.CoverEvent
import io.embodit.camcoverage.ui.cover.CoverUiState
import io.embodit.camcoverage.ui.cover.debug.probe
import io.embodit.camcoverage.ui.theme.CcColors
import io.embodit.camcoverage.ui.theme.CcType
import java.util.Locale
import kotlin.math.roundToInt

/** The 40 dp round number badge. */
@Composable
private fun Badge(n: Int, color: Color) {
    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(text = n.toString(), color = CcColors.CoverInk, style = CcType.title, maxLines = 1, softWrap = false)
    }
}

/**
 * The camera card (design `panels.cameraCard`, 400 wide, Thick glass): number, mount and height, delete;
 * lens + resolution (home) or camera type (workcell); aim buttons; tripod height; the stick hint line.
 */
@Composable
internal fun CameraCard(state: CoverUiState?, onEvent: (CoverEvent) -> Unit, head: () -> Vec3?) {
    Box(modifier = Modifier.size(PanelSizes.CARD_W.dp, PanelSizes.CARD_H.dp), contentAlignment = Alignment.TopCenter) {
        val cam = state?.selectedCamera ?: return@Box
        val ink = PicoTheme.colorScheme.labelPrimary
        val grey = PicoTheme.colorScheme.labelSecondary
        val home = state.mode == Mode.HOME
        Column(
            modifier =
                Modifier
                    .width(PanelSizes.CARD_W.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .then(glassRefresh())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ① number · mount + height · delete
            Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Badge(cam.id, if (cam.id in state.blockedCams) PicoTheme.colorScheme.error else PicoTheme.colorScheme.interaction)
                Icon(painter = painterResource(when (cam.mount) { Mount.WALL -> R.drawable.ic_mount_wall; Mount.CEILING -> R.drawable.ic_mount_ceiling; Mount.TRIPOD -> R.drawable.ic_mount_tripod }), contentDescription = mountName(cam.mount), modifier = Modifier.size(24.dp), tint = ink)
                val h = cam.heightAbove(state.room.floorY ?: 0f)
                Text(text = rs(R.string.height_above, Units.distance(h, state.unit)), color = ink, style = CcType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("card_height"))
                Key(name = "delete", icon = R.drawable.ic_trash, description = rs(R.string.action_delete), enabled = true) { onEvent(CoverEvent.DeleteCamera) }
            }
            // ② lens / resolution or type
            if (home) {
                Text(text = rs(R.string.estimate_fov, cam.lens.hfovDeg.roundToInt(), cam.lens.vfovDeg.roundToInt()), color = grey, style = CcType.label, maxLines = 1, modifier = Modifier.padding(start = 4.dp).probe("card_fov"))
                Segments(items = Lens.entries, selected = cam.lens, width = (PanelSizes.CARD_W - 32f).dp, enabled = true, name = { "lens_${it.tag.replace('.', '_')}" }, onSelect = { onEvent(CoverEvent.LensSelected(it)) }) { l ->
                    Text(text = l.tag, color = ink, style = CcType.label, maxLines = 1, softWrap = false)
                }
                Segments(items = Resolution.entries, selected = cam.res, width = (PanelSizes.CARD_W - 32f).dp, enabled = true, name = { "res_${it.tag}" }, onSelect = { onEvent(CoverEvent.ResSelected(it)) }) { r ->
                    Text(text = rs(R.string.megapixel, r.tag), color = ink, style = CcType.label, maxLines = 1, softWrap = false)
                }
                Text(text = rangeLine(cam, state.unit), color = grey, style = CcType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp).probe("card_range"))
            } else {
                Segments(items = CamType.entries, selected = cam.type, width = (PanelSizes.CARD_W - 32f).dp, enabled = true, name = { "type_${it.tag}" }, onSelect = { onEvent(CoverEvent.TypeSelected(it)) }) { t ->
                    Text(text = typeName(t), color = ink, style = CcType.label, maxLines = 1, softWrap = false)
                }
            }
            // ③ aim buttons
            Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WordButton("aim_me", R.drawable.ic_person, rs(R.string.aim_me), PicoTheme.colorScheme.fillTertiary, ink, modifier = Modifier.weight(1f)) { head()?.let { onEvent(CoverEvent.AimAtMe(it)) } }
                if (!home) WordButton("aim_zone", R.drawable.ic_target, rs(R.string.aim_zone), PicoTheme.colorScheme.fillTertiary, ink, enabled = state.zone != null, modifier = Modifier.weight(1f)) { onEvent(CoverEvent.AimAtZone) }
            }
            // ④ tripod height
            if (cam.mount == Mount.TRIPOD) {
                val lo = if (cam.onFloor) Camera.Dims.FLOOR_TRIPOD_MIN else Camera.Dims.DESK_TRIPOD_MIN
                val hi = if (cam.onFloor) Camera.Dims.FLOOR_TRIPOD_MAX else Camera.Dims.DESK_TRIPOD_MAX
                Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = rs(R.string.tripod_height), color = ink, style = CcType.label, maxLines = 1)
                    Spacer(modifier = Modifier.weight(1f))
                    Key(name = "height_minus", icon = R.drawable.ic_minus, description = rs(R.string.action_lower), enabled = cam.height > lo + 0.001f) { onEvent(CoverEvent.HeightStepped(-1)) }
                    Text(text = Units.distance(cam.height, state.unit), color = ink, style = CcType.title, maxLines = 1, softWrap = false, modifier = Modifier.probe("height_value"))
                    Key(name = "height_plus", icon = R.drawable.ic_plus, description = rs(R.string.action_higher), enabled = cam.height < hi - 0.001f) { onEvent(CoverEvent.HeightStepped(1)) }
                }
            }
            // taste profile D: the stick hint
            Text(text = rs(R.string.hint_stick), color = grey, style = CcType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp).probe("card_hint"))
        }
    }
}

/** "识别 ≤ 7.0 m · 辨认 ≤ 14 m" for a home camera (design `modes.home.cardRange`). */
@Composable
private fun rangeLine(cam: Camera, unit: io.embodit.camcoverage.domain.LengthUnit): String {
    val px = cam.px(Mode.HOME)
    val h = cam.hfov(Mode.HOME)
    val id = Optics.rangeFor(Dori.IDENTIFY, px, h)
    val rec = Optics.rangeFor(Dori.RECOGNIZE, px, h)
    return rs(R.string.range_line, doriName(Dori.IDENTIFY), Units.range(id, unit), doriName(Dori.RECOGNIZE), Units.range(rec, unit))
}

/** The work area card (design `panels.zoneCard`, 360 x 176): size, rotate, height, one verdict line. */
@Composable
internal fun ZoneCard(state: CoverUiState?, onEvent: (CoverEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.ZONE_W.dp, PanelSizes.ZONE_H.dp)
                .clip(RoundedCornerShape(28.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val zone = state?.zone ?: return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Segments(items = ZoneSize.entries, selected = zone.size, width = 260.dp, enabled = true, name = { "zone_size_${it.tag}" }, onSelect = { onEvent(CoverEvent.ZoneSizeSelected(it)) }) { s ->
                Text(text = String.format(Locale.US, "%s %d×%d", s.tag, (s.w * 100).roundToInt(), (s.d * 100).roundToInt()), color = ink, style = CcType.label, maxLines = 1, softWrap = false)
            }
            Spacer(modifier = Modifier.weight(1f))
            Key(name = "zone_rotate", icon = R.drawable.ic_rotate90, description = rs(R.string.action_rotate), enabled = true) { onEvent(CoverEvent.ZoneRotate) }
        }
        Segments(items = ZoneHeight.entries, selected = zone.height, width = (PanelSizes.ZONE_W - 32f).dp, enabled = true, name = { "zone_h_${it.tag}" }, onSelect = { onEvent(CoverEvent.ZoneHeightSelected(it)) }) { h ->
            Text(text = rs(R.string.cm, h.tag), color = ink, style = CcType.label, maxLines = 1, softWrap = false)
        }
        val cov = state.coverage?.takeIf { it.mode == Mode.WORKCELL }
        val text =
            when {
                state.cameras.isEmpty() -> rs(R.string.status_zone_empty)
                cov == null -> ""
                else -> rs(R.string.zone_verdict, (cov.twoPlusShare * 100f).roundToInt(), cov.zeroCells)
            }
        Text(text = text, color = ink, style = CcType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp).probe("zone_verdict"))
    }
}

/**
 * The number tag riding 8 cm above a camera (design `entities3d.cameraTagReadout`): a coverInk pill with
 * the number and one reading; yellow rim when selected, red face when blocked, dashed white rim while unsaved.
 */
@Composable
internal fun CameraTag(state: CoverUiState?, camera: Camera?) {
    Box(modifier = Modifier.size(PanelSizes.TAG_W.dp, PanelSizes.TAG_H.dp), contentAlignment = Alignment.Center) {
        if (state == null || camera == null) return@Box
        val blocked = camera.id in state.blockedCams && state.mode == Mode.WORKCELL
        val selected = camera.id == state.selected
        val stat = state.coverage?.takeIf { it.mode == state.mode }?.stat(camera.id)
        val text =
            when {
                blocked -> rs(R.string.tag_blocked, camera.id, ((stat?.blockedShare ?: 0f) * 100f).roundToInt())
                state.mode == Mode.HOME -> rs(R.string.tag_home, camera.id, doriName(Dori.IDENTIFY), Units.range(Optics.rangeFor(Dori.IDENTIFY, camera.px(Mode.HOME), camera.hfov(Mode.HOME)), state.unit))
                camera.isDepth -> {
                    val d = stat?.depthM
                    if (stat == null || d == null) "${camera.id}" else rs(if (stat.inDepthRange) R.string.tag_depth_ok else R.string.tag_depth_bad, camera.id, Units.range(d, state.unit))
                }
                else -> {
                    val v = stat?.value
                    if (v == null) rs(R.string.tag_no_zone, camera.id) else rs(R.string.tag_work, camera.id, Units.clarity(v, state.unit))
                }
            }
        val bg = when { state.trackingLost -> CcColors.StaleGrey; blocked -> PicoTheme.colorScheme.error; else -> CcColors.CoverInk }
        val fg = if (blocked || state.trackingLost) CcColors.CoverInk else CcColors.White
        val rim = when { state.unsaved || state.saving -> CcColors.White.copy(alpha = 0.7f); selected -> PicoTheme.colorScheme.interaction; else -> Color.Transparent }
        Row(
            modifier = Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).border(2.dp, rim, RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 12.dp).probe("tag"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (blocked) Icon(painter = painterResource(R.drawable.ic_block), contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
            else if (state.mode == Mode.WORKCELL && camera.isDepth && stat?.inDepthRange == true && stat.depthM != null) Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
            Text(text = text, color = fg, style = CcType.label, maxLines = 1, softWrap = false)
            if (state.mode == Mode.WORKCELL && !camera.isDepth && stat?.value != null && stat.value < Optics.CLARITY_OK_PX_CM) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(PicoTheme.colorScheme.alert))
            }
        }
    }
}

package io.embodit.camcoverage.ui.cover.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.camcoverage.platform.CcLog
import io.embodit.camcoverage.ui.cover.CoverEvent
import io.embodit.camcoverage.ui.cover.CoverUiState
import io.embodit.camcoverage.ui.cover.debug.LocalProbePanel
import io.embodit.camcoverage.ui.cover.debug.Probe
import io.embodit.camcoverage.ui.cover.scene.CoverScene
import io.embodit.camcoverage.ui.cover.scene.PanelIds

/**
 * The Stage content: one full-size SpatialView with the cameras, the room's tap targets and the panel
 * pool. Every attachment panel is declared once with a fixed size, shown or not ([CoverScene] parents
 * and places them). Tap and drag recognizers live in separate pointerInput blocks (Spatial Ruler).
 *
 * Window model: single panel with popups. The console is the one resident panel; the camera card,
 * zone card, tags and coachmark ride on 3D things; menu, dialog and snackbar float.
 */
@Composable
internal fun CoverStage(state: CoverUiState, scene: CoverScene, onEvent: (CoverEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value -> scene.onSpatialDrag("move", null, value) }
                },
        attachments = {
            AttachmentPanel(PanelIds.CONSOLE) {
                PoolPanel(PanelIds.CONSOLE, scene, pressed = { scene.onConsolePointer() }) { inView -> ConsolePanel(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.CARD) {
                PoolPanel(PanelIds.CARD, scene) { inView -> CameraCard(state = if (inView) state else null, onEvent = onEvent, head = { scene.headForAim() }) }
            }
            AttachmentPanel(PanelIds.ZONE_CARD) {
                PoolPanel(PanelIds.ZONE_CARD, scene) { inView -> ZoneCard(state = if (inView) state else null, onEvent = onEvent) }
            }
            for (i in 0 until PanelIds.TAG_SLOTS) {
                AttachmentPanel(PanelIds.tag(i)) {
                    PoolPanel(PanelIds.tag(i), scene) { inView -> CameraTag(state = if (inView) state else null, camera = if (inView) scene.tagCamera(i, state) else null) }
                }
            }
            AttachmentPanel(PanelIds.MENU) {
                PoolPanel(PanelIds.MENU, scene, pressed = { scene.onConsolePointer() }) { inView -> MoreMenu(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.DIALOG) {
                PoolPanel(PanelIds.DIALOG, scene) { inView -> DialogPanel(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.SNACK) {
                PoolPanel(PanelIds.SNACK, scene) { inView -> SnackPanel(snack = if (inView) state.snack else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.COACH) {
                PoolPanel(PanelIds.COACH, scene) { inView -> CoachPanel(state = if (inView && state.coach) state else null, onEvent = onEvent) }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel: [content] gets `inView` (the panel has been moved into view; a pooled panel
 * draws only then, Spatial Ruler finding 3). Debug builds log every press as `PTR panel=<id> ev=`.
 */
@Composable
private fun PoolPanel(id: String, scene: CoverScene, pressed: (() -> Unit)? = null, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val logged = Probe.enabled
    val watcher =
        if (logged || pressed != null) {
            Modifier.pointerInput(id, logged) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) continue
                        if (event.type == PointerEventType.Press) pressed?.invoke()
                        if (logged) {
                            val p = event.changes.firstOrNull()?.position
                            CcLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                        }
                    }
                }
            }
        } else Modifier
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}

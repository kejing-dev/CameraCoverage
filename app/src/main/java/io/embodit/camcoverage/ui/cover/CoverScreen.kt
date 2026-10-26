package io.embodit.camcoverage.ui.cover

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.camcoverage.platform.CcLog
import io.embodit.camcoverage.ui.cover.components.CoverStage
import io.embodit.camcoverage.ui.cover.components.LocalCcResources
import io.embodit.camcoverage.ui.cover.components.localizedResources
import io.embodit.camcoverage.ui.cover.debug.Probe
import io.embodit.camcoverage.ui.cover.scene.CoverFrameSystem
import io.embodit.camcoverage.ui.cover.scene.CoverScene

/**
 * The one screen of the app, in a Mixed Stage (Full Space). It wires the ViewModel (state), the
 * [CoverScene] (entities, per-frame work, taps and drags) and [CoverStage] (the panels), and owns nothing.
 */
@Composable
fun CoverScreen() {
    val context = LocalContext.current
    val viewModel: CoverViewModel = viewModel(factory = CoverViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { CoverScene(viewModel::onEvent).also { s -> viewModel.headProvider = { s.headForAim() } } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        // the one per-frame system; every start has its stop in onDispose
        registerSystem<CoverFrameSystem>()
        Probe.host =
            object : Probe.Host {
                override fun viewModel(): CoverViewModel = viewModel

                override fun scene(): CoverScene = scene
            }
        // in the background every tracker stops (design `container.lifecycle`)
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> scene.pause()
                    Lifecycle.Event.ON_START -> scene.resume()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        CcLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<CoverFrameSystem>()
            scene.detach()
            CcLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CoverEffect.Exit -> {
                    CcLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { CcLog.e("STAGE close failed: $it", it) }
                }
                is CoverEffect.Feedback ->
                    runCatching {
                        audio.playSystem(if (effect.kind == "snap") SpatialSoundEffect.StateSelected else SpatialSoundEffect.OpClick)
                    }.onFailure { CcLog.w("FEEDBACK failed: $it") }
            }
        }
    }

    // the in-app language switches at run time: panels read their copy from this local
    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    CompositionLocalProvider(LocalCcResources provides resources) {
        CoverStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}

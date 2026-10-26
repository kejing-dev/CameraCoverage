package io.embodit.camcoverage.platform

import android.content.Context
import io.embodit.camcoverage.data.PrefsLayoutStore
import io.embodit.camcoverage.platform.anchor.WorldAnchorGateway
import io.embodit.camcoverage.platform.sense.PlaneTracker
import io.embodit.camcoverage.platform.tracking.ControllerStick
import io.embodit.camcoverage.platform.tracking.HeadTracker

/**
 * Process-wide platform services. The scene starts and stops the sensing services (every start has a
 * stop: `CoverScene.attach` / `detach`); the ViewModel only sees interfaces and lambdas.
 */
object CcGraph {
    val anchors = WorldAnchorGateway()
    val planes = PlaneTracker()
    val head = HeadTracker()
    val stick = ControllerStick()

    @Volatile private var store: PrefsLayoutStore? = null

    fun store(context: Context): PrefsLayoutStore = store ?: synchronized(this) { store ?: PrefsLayoutStore(context).also { store = it } }
}

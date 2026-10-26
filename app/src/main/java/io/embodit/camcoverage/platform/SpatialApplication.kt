package io.embodit.camcoverage.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.camcoverage.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}

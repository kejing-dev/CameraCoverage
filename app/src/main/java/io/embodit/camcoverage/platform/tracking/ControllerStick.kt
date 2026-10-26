package io.embodit.camcoverage.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.camcoverage.platform.CcLog
import kotlin.math.abs

/**
 * The controllers' thumbstick X axis (v0.1.1, device feedback: the stick turns the selected piece),
 * from `ControllerTrackingProvider`'s action listener (SDK doc "Obtain controller input actions"; Full
 * Space only). The listener runs on the data source thread at the device rate: it only stores the
 * value; the ECS system reads it once per frame. Hand gestures are untouched (another input path).
 */
class ControllerStick {
    private var provider: ControllerTrackingProvider? = null

    @Volatile private var x = 0f
    @Volatile private var atMs = 0L
    @Volatile var actions = 0L
        private set

    /** Debug: a stick value that wins over the controllers until [debugUntilMs] (emulator QA). */
    @Volatile private var debugX = 0f
    @Volatile private var debugUntilMs = 0L

    // the stick pushed further of the two controllers
    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a ->
            val l = a.left.thumbstickValue.x
            val r = a.right.thumbstickValue.x
            x = if (abs(r) >= abs(l)) r else l
            atMs = SystemClock.elapsedRealtime()
            actions++
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }.onFailure { CcLog.w("STICK listener: $it") }
        val result = runCatching { p.start() }
        CcLog.i("STICK start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        x = 0f
        actions = 0L
    }

    /** Thumbstick X in [-1, 1] (right +); 0 without a controller or when the last report is stale. */
    fun x(nowMs: Long): Float {
        if (nowMs < debugUntilMs) return debugX
        val p = provider ?: return 0f
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return 0f
        if (nowMs - atMs > STALE_MS) return 0f
        return x.coerceIn(-1f, 1f)
    }

    /** Debug `stick`: holds [value] for [ms]. */
    fun debugHold(value: Float, ms: Long) {
        debugX = value.coerceIn(-1f, 1f)
        debugUntilMs = SystemClock.elapsedRealtime() + ms
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actions x=${"%.2f".format(x)}"

    private companion object {
        const val STALE_MS = 500L
    }
}

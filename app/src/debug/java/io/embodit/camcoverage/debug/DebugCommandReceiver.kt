package io.embodit.camcoverage.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.camcoverage.platform.CcLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action
 * `io.embodit.camcoverage.DEBUG`); see [DebugCommands] for the command list.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.camcoverage.DEBUG -p io.embodit.camcoverage --es cmd state --es s S10"
 *
 * Extras (design `qaHooks`): `cmd`, text `s` / `v` / `mount` / `plane`, ints `lens` / `res` / `type` /
 * `size` / `h` / `id`, floats `x` / `y` / `z` / `yaw` / `pitch`.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val texts = HashMap<String, String>()
        for (k in listOf("s", "v", "mount", "plane", "a")) intent.getStringExtra(k)?.let { texts[k] = it }
        val ints = HashMap<String, Int>()
        for (k in listOf("lens", "res", "type", "size", "h", "id", "n")) if (intent.hasExtra(k)) ints[k] = intent.getIntExtra(k, 0)
        val floats = HashMap<String, Float>()
        for (k in listOf("x", "y", "z", "yaw", "pitch", "fx", "fz")) if (intent.hasExtra(k)) floats[k] = intent.getFloatExtra(k, 0f)
        CcLog.i("CMD recv '$cmd' s=$texts i=$ints f=$floats")
        // broadcasts arrive on the main thread, which is where the ViewModel and the scene live
        DebugCommands.run(context.applicationContext, cmd, texts, ints, floats)
    }
}

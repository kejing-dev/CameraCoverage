package io.embodit.camcoverage.data

import android.content.Context
import io.embodit.camcoverage.domain.AnchorPose
import io.embodit.camcoverage.domain.LanguagePref
import io.embodit.camcoverage.domain.LayoutCodec
import io.embodit.camcoverage.domain.LengthUnit
import io.embodit.camcoverage.domain.Mode
import io.embodit.camcoverage.domain.SavedLayout
import io.embodit.camcoverage.domain.Settings
import io.embodit.camcoverage.domain.Vec3
import java.util.Locale

/** A world anchor as the app sees it. [id] is the platform's UUID as a string. */
data class AnchorRecord(val id: String, val pose: AnchorPose)

sealed interface AnchorOutcome<out T> {
    data class Ok<T>(val value: T) : AnchorOutcome<T>

    data class Failed(val code: Int, val message: String) : AnchorOutcome<Nothing>
}

/** The platform's persistent anchors (`platform.anchor.WorldAnchorGateway`; a fake in tests). */
interface AnchorGateway {
    suspend fun create(position: Vec3, name: String): AnchorOutcome<AnchorRecord>

    /** All anchors of this app in this space; an empty store is `Ok(emptyList())`. */
    suspend fun loadAll(): AnchorOutcome<List<AnchorRecord>>

    suspend fun remove(anchorId: String): AnchorOutcome<Unit>
}

/** Layouts and settings on the device (no network, no account). */
interface LayoutStore {
    /** The saved anchor id, or null when nothing is saved. */
    fun savedAnchorId(): String?

    /** Both layouts in Stage metres for the anchor pose found at load time. */
    fun loadLayout(anchor: AnchorPose): SavedLayout?

    fun saveLayout(layout: SavedLayout?, anchor: AnchorPose?)

    fun loadSettings(): Settings

    fun saveSettings(settings: Settings)
}

class PrefsLayoutStore(context: Context) : LayoutStore {
    private val prefs = context.getSharedPreferences("cam_coverage", Context.MODE_PRIVATE)

    /** Debug: the next write fails (design S19). */
    @Volatile var failNextSave = false

    override fun savedAnchorId(): String? = LayoutCodec.anchorIdOf(prefs.getString(KEY_LAYOUT, null))

    override fun loadLayout(anchor: AnchorPose): SavedLayout? = LayoutCodec.decode(prefs.getString(KEY_LAYOUT, null), anchor)

    override fun saveLayout(layout: SavedLayout?, anchor: AnchorPose?) {
        if (failNextSave) {
            failNextSave = false
            throw IllegalStateException("debug save_fail")
        }
        prefs.edit().apply { if (layout == null || anchor == null) remove(KEY_LAYOUT) else putString(KEY_LAYOUT, LayoutCodec.encode(layout, anchor)) }.commit()
    }

    override fun loadSettings(): Settings =
        Settings(
            language = LanguagePref.entries.firstOrNull { it.tag == prefs.getString("lang", null) } ?: LanguagePref.SYSTEM,
            unit = LengthUnit.entries.firstOrNull { it.tag == prefs.getString("unit", null) } ?: LengthUnit.defaultFor(Locale.getDefault().country),
            lastMode = Mode.byTag(prefs.getString("mode", null)),
            coachSeenWork = prefs.getBoolean("coachWork", false),
            coachSeenHome = prefs.getBoolean("coachHome", false),
        )

    override fun saveSettings(settings: Settings) {
        prefs.edit()
            .putString("lang", settings.language.tag)
            .putString("unit", settings.unit.tag)
            .putString("mode", settings.lastMode.tag)
            .putBoolean("coachWork", settings.coachSeenWork)
            .putBoolean("coachHome", settings.coachSeenHome)
            .apply()
    }

    private companion object {
        const val KEY_LAYOUT = "layout"
    }
}

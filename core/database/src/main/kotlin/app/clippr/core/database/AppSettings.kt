package app.clippr.core.database

import android.content.Context
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.time.FrameRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences. Stored on the device only. */
data class SettingsValues(
    val defaultAspect: AspectRatioPreset = AspectRatioPreset.LANDSCAPE_16_9,
    val defaultShortSide: Int = 1080,
    val defaultFrameRate: FrameRate = FrameRate.FPS_30,
    /** Name of a `PreviewQuality` value (core:render). */
    val previewQuality: String = "BALANCED",
    /** Name of an `ExportQuality` value (core:render). */
    val exportQuality: String = "STANDARD",
    /** Copy files picked from cloud apps (Google Drive and similar) into Clippr's storage. */
    val copyCloudFiles: Boolean = true,
    /** Show the 3-2-1 countdown before a voiceover starts recording. */
    val voiceoverCountdown: Boolean = true,
) {
    fun newProjectSettings(): ProjectSettings = ProjectSettings.create(defaultAspect, defaultShortSide, defaultFrameRate)
}

/** Reads and writes [SettingsValues] in SharedPreferences and publishes changes as a flow. */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("clippr_settings", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(read())
    val values: StateFlow<SettingsValues> = _values.asStateFlow()

    val current: SettingsValues get() = _values.value

    fun update(transform: (SettingsValues) -> SettingsValues) {
        val next = transform(_values.value)
        if (next == _values.value) return
        prefs.edit()
            .putString(KEY_ASPECT, next.defaultAspect.name)
            .putInt(KEY_SHORT_SIDE, next.defaultShortSide)
            .putInt(KEY_FPS_NUM, next.defaultFrameRate.numerator)
            .putInt(KEY_FPS_DEN, next.defaultFrameRate.denominator)
            .putString(KEY_PREVIEW, next.previewQuality)
            .putString(KEY_EXPORT, next.exportQuality)
            .putBoolean(KEY_COPY_CLOUD, next.copyCloudFiles)
            .putBoolean(KEY_COUNTDOWN, next.voiceoverCountdown)
            .apply()
        _values.value = next
    }

    private fun read(): SettingsValues {
        val d = SettingsValues()
        return SettingsValues(
            defaultAspect = AspectRatioPreset.entries.firstOrNull { it.name == prefs.getString(KEY_ASPECT, null) } ?: d.defaultAspect,
            defaultShortSide = prefs.getInt(KEY_SHORT_SIDE, d.defaultShortSide).takeIf { it in setOf(720, 1080, 1440, 2160) } ?: d.defaultShortSide,
            defaultFrameRate = FrameRate(
                prefs.getInt(KEY_FPS_NUM, d.defaultFrameRate.numerator).coerceAtLeast(1),
                prefs.getInt(KEY_FPS_DEN, d.defaultFrameRate.denominator).coerceAtLeast(1),
            ),
            previewQuality = prefs.getString(KEY_PREVIEW, null) ?: d.previewQuality,
            exportQuality = prefs.getString(KEY_EXPORT, null) ?: d.exportQuality,
            copyCloudFiles = prefs.getBoolean(KEY_COPY_CLOUD, d.copyCloudFiles),
            voiceoverCountdown = prefs.getBoolean(KEY_COUNTDOWN, d.voiceoverCountdown),
        )
    }

    private companion object {
        const val KEY_ASPECT = "default_aspect"
        const val KEY_SHORT_SIDE = "default_short_side"
        const val KEY_FPS_NUM = "default_fps_num"
        const val KEY_FPS_DEN = "default_fps_den"
        const val KEY_PREVIEW = "preview_quality"
        const val KEY_EXPORT = "export_quality"
        const val KEY_COPY_CLOUD = "copy_cloud_files"
        const val KEY_COUNTDOWN = "voiceover_countdown"
    }
}

package com.metahumanz.pacilread.reader

import com.metahumanz.pacilread.storage.SettingsStore
import org.json.JSONObject

object ReaderThemeConfig {
    @JvmStatic
    fun export(settingsStore: SettingsStore): JSONObject {
        val jsonObject = JSONObject()
        try {
            jsonObject.put("font_size_sp", settingsStore.fontSizeSp)
            jsonObject.put("font_family", settingsStore.readerFontFamily)
            jsonObject.put("font_weight", settingsStore.readerFontWeight)
            jsonObject.put("text_color", settingsStore.readerTextColor)
            jsonObject.put("line_spacing_extra", settingsStore.lineSpacingExtraSp)
            jsonObject.put("paragraph_spacing_dp", settingsStore.paragraphSpacingDp)
            jsonObject.put("left_padding_dp", settingsStore.leftPaddingDp)
            jsonObject.put("right_padding_dp", settingsStore.rightPaddingDp)
            jsonObject.put("top_padding_dp", settingsStore.topPaddingDp)
            jsonObject.put("bottom_padding_dp", settingsStore.bottomPaddingDp)
            jsonObject.put("reader_theme", settingsStore.readerTheme)
            jsonObject.put("reader_background_path", settingsStore.readerBackgroundPath)
            jsonObject.put("keep_screen_on", settingsStore.isKeepScreenOn)
            jsonObject.put("flip_mode", settingsStore.flipMode)
            jsonObject.put("reader_simulationDoublePageTurnMode", settingsStore.simulationDoublePageTurnMode)
            jsonObject.put("chapter_title_visibility", settingsStore.isChapterTitleVisible)
            jsonObject.put("auto_page_seconds", settingsStore.autoPageSeconds)
            jsonObject.put("tts_rate", settingsStore.ttsRate)
            jsonObject.put("tts_mimo_voice", settingsStore.ttsMimoVoice)
        } catch (_: Exception) {
        }
        return jsonObject
    }

    @JvmStatic
    fun apply(settingsStore: SettingsStore, jsonObject: JSONObject?) {
        if (jsonObject == null) return
        settingsStore.fontSizeSp = jsonObject.optDouble("font_size_sp", settingsStore.fontSizeSp.toDouble()).toFloat()
        settingsStore.readerFontFamily = jsonObject.optString("font_family", settingsStore.readerFontFamily)
        settingsStore.readerFontWeight = jsonObject.optInt("font_weight", settingsStore.readerFontWeight)
        settingsStore.readerTextColor = jsonObject.optString("text_color", settingsStore.readerTextColor)
        settingsStore.lineSpacingExtraSp = jsonObject.optDouble("line_spacing_extra", settingsStore.lineSpacingExtraSp.toDouble()).toFloat()
        settingsStore.paragraphSpacingDp = jsonObject.optInt("paragraph_spacing_dp", settingsStore.paragraphSpacingDp)
        settingsStore.leftPaddingDp = jsonObject.optInt("left_padding_dp", settingsStore.leftPaddingDp)
        settingsStore.rightPaddingDp = jsonObject.optInt("right_padding_dp", settingsStore.rightPaddingDp)
        settingsStore.topPaddingDp = jsonObject.optInt("top_padding_dp", settingsStore.topPaddingDp)
        settingsStore.bottomPaddingDp = jsonObject.optInt("bottom_padding_dp", settingsStore.bottomPaddingDp)
        settingsStore.readerTheme = jsonObject.optString("reader_theme", settingsStore.readerTheme)
        settingsStore.readerBackgroundPath = jsonObject.optString("reader_background_path", settingsStore.readerBackgroundPath)
        settingsStore.isKeepScreenOn = jsonObject.optBoolean("keep_screen_on", settingsStore.isKeepScreenOn)
        settingsStore.flipMode = jsonObject.optString("flip_mode", settingsStore.flipMode)
        settingsStore.simulationDoublePageTurnMode = jsonObject.optString("reader_simulationDoublePageTurnMode", settingsStore.simulationDoublePageTurnMode)
        settingsStore.isChapterTitleVisible = jsonObject.optBoolean("chapter_title_visibility", settingsStore.isChapterTitleVisible)
        settingsStore.autoPageSeconds = jsonObject.optInt("auto_page_seconds", settingsStore.autoPageSeconds)
        settingsStore.ttsRate = jsonObject.optDouble("tts_rate", settingsStore.ttsRate.toDouble()).toFloat()
        settingsStore.ttsMimoVoice = jsonObject.optString("tts_mimo_voice", settingsStore.ttsMimoVoice)
    }
}

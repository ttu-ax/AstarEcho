package com.astraecho.rhythm.data

import android.content.Context
import com.astraecho.rhythm.model.Difficulty
import com.astraecho.rhythm.model.NoteSkin
import com.astraecho.rhythm.model.PlayerSettings

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("astra_echo_prefs", Context.MODE_PRIVATE)

    fun load(): PlayerSettings {
        val diffName = prefs.getString("difficulty", Difficulty.NORMAL.name) ?: Difficulty.NORMAL.name
        val diff = runCatching { Difficulty.valueOf(diffName) }.getOrDefault(Difficulty.NORMAL)
        val skinName = prefs.getString("skin", NoteSkin.ASTRA_CYBER.name) ?: NoteSkin.ASTRA_CYBER.name
        val skin = runCatching { NoteSkin.valueOf(skinName) }.getOrDefault(NoteSkin.ASTRA_CYBER)

        return PlayerSettings(
            trackTiltDegrees = prefs.getFloat("trackTiltDegrees", 42f).coerceIn(0f, 60f),
            noteSpeed = prefs.getFloat("noteSpeed", 1.05f).coerceIn(0.6f, 1.8f),
            laneCount = prefs.getInt("laneCount", 4).coerceIn(4, 6),
            difficulty = diff,
            onlyTapAndHold = prefs.getBoolean("onlyTapAndHold", false),
            audioOffsetMs = prefs.getInt("audioOffsetMs", 0).coerceIn(-150, 150),
            skin = skin,
            customParserApiUrl = prefs.getString("customParserApiUrl", "") ?: ""
        )
    }

    fun save(settings: PlayerSettings) {
        prefs.edit()
            .putFloat("trackTiltDegrees", settings.trackTiltDegrees)
            .putFloat("noteSpeed", settings.noteSpeed)
            .putInt("laneCount", settings.laneCount)
            .putString("difficulty", settings.difficulty.name)
            .putBoolean("onlyTapAndHold", settings.onlyTapAndHold)
            .putInt("audioOffsetMs", settings.audioOffsetMs)
            .putString("skin", settings.skin.name)
            .putString("customParserApiUrl", settings.customParserApiUrl)
            .apply()
    }
}

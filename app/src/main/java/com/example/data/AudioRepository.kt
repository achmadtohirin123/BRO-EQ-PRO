package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AudioRepository(private val presetDao: PresetDao) {
    val customPresets: Flow<List<PresetEntity>> = presetDao.getAllPresets()
    val appSettings: Flow<AppSettingsEntity> = presetDao.getSettingsFlow().map { it ?: AppSettingsEntity() }

    suspend fun getSettingsDirect(): AppSettingsEntity {
        return presetDao.getSettingsDirect() ?: AppSettingsEntity()
    }

    suspend fun saveSettings(settings: AppSettingsEntity) {
        presetDao.saveSettings(settings)
    }

    suspend fun insertCustomPreset(name: String, bandCount: Int, gains: List<Float>): Long {
        val gainsStr = gains.joinToString(",") { String.format("%.1f", it) }
        val entity = PresetEntity(name = name, isCustom = true, bandCount = bandCount, gainsJson = gainsStr)
        return presetDao.insertPreset(entity)
    }

    suspend fun deletePreset(id: Int) {
        presetDao.deletePreset(id)
    }

    // Returns a list of default presets defined by the DAW studio engineering presets
    fun getDefaultPresets(bandCount: Int): List<DefaultPreset> {
        return listOf(
            DefaultPreset("Flat", getPresetGains("Flat", bandCount)),
            DefaultPreset("Bass Boost", getPresetGains("Bass Boost", bandCount)),
            DefaultPreset("Vocal", getPresetGains("Vocal", bandCount)),
            DefaultPreset("EDM", getPresetGains("EDM", bandCount)),
            DefaultPreset("Rock", getPresetGains("Rock", bandCount)),
            DefaultPreset("Jazz", getPresetGains("Jazz", bandCount)),
            DefaultPreset("Dangdut", getPresetGains("Dangdut", bandCount)),
            DefaultPreset("Koplo", getPresetGains("Koplo", bandCount)),
            DefaultPreset("DJ Club", getPresetGains("DJ Club", bandCount)),
            DefaultPreset("Cinema", getPresetGains("Cinema", bandCount)),
            DefaultPreset("Gaming", getPresetGains("Gaming", bandCount)),
            DefaultPreset("Podcast", getPresetGains("Podcast", bandCount))
        )
    }

    private fun getPresetGains(presetName: String, bandCount: Int): List<Float> {
        val baseGains = when (presetName) {
            "Bass Boost" -> listOf(6.0f, 5.0f, 2.5f, 0.0f, -1.0f, -2.0f, -1.0f)
            "Vocal" -> listOf(-3.0f, -1.5f, 1.0f, 4.0f, 4.5f, 3.0f, 1.0f)
            "EDM" -> listOf(5.0f, 3.5f, -1.0f, 1.5f, 2.5f, 4.0f, 5.0f)
            "Rock" -> listOf(3.5f, 2.0f, -1.5f, -1.0f, 1.5f, 2.5f, 4.0f)
            "Jazz" -> listOf(3.0f, 2.0f, 1.0f, -1.5f, 1.5f, 2.0f, 3.0f)
            "Dangdut" -> listOf(4.5f, 3.5f, 2.0f, -1.0f, 0.5f, 1.5f, 3.5f)
            "Koplo" -> listOf(5.5f, 4.0f, 1.5f, -1.5f, 1.0f, 2.5f, 4.5f)
            "DJ Club" -> listOf(6.0f, 4.5f, 0.5f, 1.0f, 2.0f, 3.5f, 5.5f)
            "Cinema" -> listOf(4.0f, 2.5f, 0.0f, -1.0f, 1.0f, 3.0f, 4.5f)
            "Gaming" -> listOf(4.5f, 3.0f, 1.0f, 0.0f, 2.0f, 3.5f, 4.0f)
            "Podcast" -> listOf(-4.0f, -1.0f, 3.0f, 4.5f, 3.5f, 1.5f, -1.5f)
            else -> List(7) { 0.0f } // Flat
        }
        return interpolateGains(baseGains, bandCount)
    }

    private fun interpolateGains(baseGains: List<Float>, targetCount: Int): List<Float> {
        val list = ArrayList<Float>()
        for (i in 0 until targetCount) {
            val t = i.toFloat() / (targetCount - 1).coerceAtLeast(1)
            val baseIndexF = t * (baseGains.size - 1)
            val index1 = baseIndexF.toInt()
            val index2 = (index1 + 1).coerceAtMost(baseGains.size - 1)
            val fract = baseIndexF - index1
            val gain = baseGains[index1] * (1f - fract) + baseGains[index2] * fract
            list.add(gain)
        }
        return list
    }
}

data class DefaultPreset(
    val name: String,
    val gains: List<Float>
)

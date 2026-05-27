package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val isCustom: Boolean,
    val bandCount: Int,
    val gainsJson: String // Serialized array of band gains e.g., "0,1.2,-2.1,0,3"
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val engineEnabled: Boolean = true,
    val currentBandCount: Int = 10,
    val currentPresetId: Int = 0, // 0 for Flat, negative for default presets, positive for custom presets
    val presetName: String = "Flat",
    val bassBoost: Float = 40f,
    val midEnhancer: Float = 20f,
    val trebleEnhancer: Float = 30f,
    val lowFreqControl: Float = 50f,
    val subwooferEnhancer: Float = 40f,
    val vocalClarity: Float = 60f,
    val audioSoftener: Float = 10f,
    val audioThickener: Float = 25f,
    val dynamicEnhancer: Float = 35f,
    val stereoWidener: Float = 50f,
    val extremeStereo: Float = 15f,
    val threeDAudio: Float = 30f,
    val surroundAudio: Float = 20f,
    val reverbLevel: Float = 30f,
    val delayMs: Float = 120f,
    val delayFeedback: Float = 40f,
    val harmonicExciter: Float = 25f,
    val compressorThreshold: Float = -24f,
    val compressorRatio: Float = 4f,
    val limiterThreshold: Float = -1.5f,
    val crossoverFreq: Float = 120f,
    val noiseReduction: Float = 15f,
    val loudnessMaximizer: Float = 40f,
    val tubeWarmth: Float = 35f,
    val analogSaturation: Float = 25f,
    val spatialAudio: Boolean = true,
    val preampGain: Float = 0f,
    val inputGain: Float = 0f,
    val outputGain: Float = 0f,
    val balanceLeftRight: Float = 0f, // -50 to 50
    val dynamicEQ: Boolean = true,
    val themeColor: String = "Neon Cyan" // Neon Cyan, Neon Blue, Neon Red, Neon Purple, Neon Green, Gold
)

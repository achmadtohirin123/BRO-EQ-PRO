package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.engine.AudioEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AudioViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val repository = AudioRepository(db.presetDao())
    val audioEngine = AudioEngine(application)

    // UI statuses from AudioEngine
    val engineActive = audioEngine.engineActive
    val playbackActive = audioEngine.playbackActive
    val spectrumData = audioEngine.spectrumData
    val vuLeft = audioEngine.vuLeft
    val vuRight = audioEngine.vuRight
    val peakLeft = audioEngine.peakLeft
    val peakRight = audioEngine.peakRight
    val currentTrackInfo = audioEngine.currentTrackInfo

    // Db states
    val customPresets = repository.customPresets.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _settings = MutableStateFlow(AppSettingsEntity())
    val settings: StateFlow<AppSettingsEntity> = _settings.asStateFlow()

    // Current active EQ bands gains list
    private val _currentGains = MutableStateFlow<List<Float>>(emptyList())
    val currentGains: StateFlow<List<Float>> = _currentGains.asStateFlow()

    init {
        // Collect settings and push to AudioEngine
        viewModelScope.launch {
            repository.appSettings.collect { loaded ->
                _settings.value = loaded
                // Reload current gains based on active selection
                val gains = parseGains(loaded.currentPresetId, loaded.currentBandCount, loaded.presetName)
                _currentGains.value = gains
                audioEngine.updateSettings(loaded, gains)

                // Sync engine active status
                audioEngine.toggleEngine(loaded.engineEnabled)
            }
        }
    }

    private suspend fun parseGains(presetId: Int, bandCount: Int, presetName: String): List<Float> {
        if (presetId > 0) {
            // Find custom preset in db
            val list = customPresets.value
            val match = list.find { it.id == presetId }
            if (match != null) {
                return parseCsv(match.gainsJson, bandCount)
            }
        }

        // Custom preset from sliding values
        if (presetId == 9999) {
            val dbSettings = repository.getSettingsDirect()
            return parseGainsCsv(dbSettings.presetName, bandCount) // stored slider csv
        }

        // Default system preset
        val defaults = repository.getDefaultPresets(bandCount)
        val matchDefault = defaults.find { it.name == presetName }
        return matchDefault?.gains ?: List(bandCount) { 0.0f }
    }

    private fun parseCsv(csv: String, targetCount: Int): List<Float> {
        return try {
            val list = csv.split(",").mapNotNull { it.toFloatOrNull() }
            if (list.size == targetCount) list else interpolateList(list, targetCount)
        } catch (e: Exception) {
            List(targetCount) { 0.0f }
        }
    }

    private suspend fun parseGainsCsv(csv: String, targetCount: Int): List<Float> {
        // If stored presets aren't present we parse from cached or settings
        val direct = repository.getSettingsDirect()
        // We will store current slider gains as comma separated values inside presetName
        return try {
            val list = direct.presetName.split(",").mapNotNull { it.toFloatOrNull() }
            if (list.size == targetCount) list else interpolateList(list, targetCount)
        } catch (e: Exception) {
            List(targetCount) { 0.0f }
        }
    }

    private fun interpolateList(source: List<Float>, targetSize: Int): List<Float> {
        if (source.isEmpty()) return List(targetSize) { 0.0f }
        val out = ArrayList<Float>()
        for (i in 0 until targetSize) {
            val t = i.toFloat() / (targetSize - 1).coerceAtLeast(1)
            val indexF = t * (source.size - 1)
            val idx1 = indexF.toInt()
            val idx2 = (idx1 + 1).coerceAtMost(source.size - 1)
            val fract = indexF - idx1
            out.add(source[idx1] * (1f - fract) + source[idx2] * fract)
        }
        return out
    }

    // Engine operations
    fun toggleEngine() {
        viewModelScope.launch {
            val current = _settings.value
            val updated = current.copy(engineEnabled = !current.engineEnabled)
            repository.saveSettings(updated)
            audioEngine.toggleEngine(updated.engineEnabled)
        }
    }

    fun togglePlayback() {
        audioEngine.togglePlay(!playbackActive.value)
    }

    // Update individual sliders
    fun updateBandCount(bands: Int) {
        viewModelScope.launch {
            val current = _settings.value
            // Build flat gains list for the new count
            val defaults = repository.getDefaultPresets(bands)
            val flatPreset = defaults.first { it.name == "Flat" }
            val flatCsv = flatPreset.gains.joinToString(",") { String.format("%.1f", it) }

            val updated = current.copy(
                currentBandCount = bands,
                currentPresetId = 0, // Reset to flat
                presetName = "Flat"
            )
            repository.saveSettings(updated)
        }
    }

    fun updateBandGain(index: Int, gainValue: Float) {
        viewModelScope.launch {
            val currentList = _currentGains.value.toMutableList()
            if (index in currentList.indices) {
                currentList[index] = gainValue
                _currentGains.value = currentList

                // Store current progress serialized as a custom slider preset (ID = 9999)
                val csv = currentList.joinToString(",") { String.format("%.1f", it) }
                val currentSettings = _settings.value
                val updated = currentSettings.copy(
                    currentPresetId = 9999, // Custom modified sliders
                    presetName = csv
                )
                repository.saveSettings(updated)
                audioEngine.updateSettings(updated, currentList)
            }
        }
    }

    fun selectPreset(presetName: String) {
        viewModelScope.launch {
            val current = _settings.value
            val updated = current.copy(
                currentPresetId = 0, // Default preset flag
                presetName = presetName
            )
            repository.saveSettings(updated)
        }
    }

    fun selectCustomPreset(preset: PresetEntity) {
        viewModelScope.launch {
            val current = _settings.value
            val updated = current.copy(
                currentPresetId = preset.id,
                currentBandCount = preset.bandCount,
                presetName = preset.name
            )
            repository.saveSettings(updated)
        }
    }

    fun saveCustomPreset(name: String) {
        viewModelScope.launch {
            val gains = _currentGains.value
            val bandCount = _settings.value.currentBandCount
            val insertId = repository.insertCustomPreset(name, bandCount, gains)

            val current = _settings.value
            val updated = current.copy(
                currentPresetId = insertId.toInt(),
                presetName = name
            )
            repository.saveSettings(updated)
        }
    }

    fun deleteCustomPreset(id: Int) {
        viewModelScope.launch {
            repository.deletePreset(id)
            // If deleting the active preset, reset to Flat
            val current = _settings.value
            if (current.currentPresetId == id) {
                val updated = current.copy(
                    currentPresetId = 0,
                    presetName = "Flat"
                )
                repository.saveSettings(updated)
            }
        }
    }

    // Settings modifications
    fun updateBassBoost(value: Float) {
        updateSettingField { it.copy(bassBoost = value) }
    }

    fun updateMidEnhancer(value: Float) {
        updateSettingField { it.copy(midEnhancer = value) }
    }

    fun updateTrebleEnhancer(value: Float) {
        updateSettingField { it.copy(trebleEnhancer = value) }
    }

    fun updateLowFreqControl(value: Float) {
        updateSettingField { it.copy(lowFreqControl = value) }
    }

    fun updateSubwooferEnhancer(value: Float) {
        updateSettingField { it.copy(subwooferEnhancer = value) }
    }

    fun updateVocalClarity(value: Float) {
        updateSettingField { it.copy(vocalClarity = value) }
    }

    fun updateAudioSoftener(value: Float) {
        updateSettingField { it.copy(audioSoftener = value) }
    }

    fun updateAudioThickener(value: Float) {
        updateSettingField { it.copy(audioThickener = value) }
    }

    fun updateDynamicEnhancer(value: Float) {
        updateSettingField { it.copy(dynamicEnhancer = value) }
    }

    fun updateStereoWidener(value: Float) {
        updateSettingField { it.copy(stereoWidener = value) }
    }

    fun updateExtremeStereo(value: Float) {
        updateSettingField { it.copy(extremeStereo = value) }
    }

    fun updateThreeDAudio(value: Float) {
        updateSettingField { it.copy(threeDAudio = value) }
    }

    fun updateSurroundAudio(value: Float) {
        updateSettingField { it.copy(surroundAudio = value) }
    }

    fun updateReverbLevel(value: Float) {
        updateSettingField { it.copy(reverbLevel = value) }
    }

    fun updateDelayMs(value: Float) {
        updateSettingField { it.copy(delayMs = value) }
    }

    fun updateDelayFeedback(value: Float) {
        updateSettingField { it.copy(delayFeedback = value) }
    }

    fun updateHarmonicExciter(value: Float) {
        updateSettingField { it.copy(harmonicExciter = value) }
    }

    fun updateCompressorThreshold(value: Float) {
        updateSettingField { it.copy(compressorThreshold = value) }
    }

    fun updateCompressorRatio(value: Float) {
        updateSettingField { it.copy(compressorRatio = value) }
    }

    fun updateLimiterThreshold(value: Float) {
        updateSettingField { it.copy(limiterThreshold = value) }
    }

    fun updateCrossoverFreq(value: Float) {
        updateSettingField { it.copy(crossoverFreq = value) }
    }

    fun updateNoiseReduction(value: Float) {
        updateSettingField { it.copy(noiseReduction = value) }
    }

    fun updateLoudnessMaximizer(value: Float) {
        updateSettingField { it.copy(loudnessMaximizer = value) }
    }

    fun updateTubeWarmth(value: Float) {
        updateSettingField { it.copy(tubeWarmth = value) }
    }

    fun updateAnalogSaturation(value: Float) {
        updateSettingField { it.copy(analogSaturation = value) }
    }

    fun updateSpatialAudio(value: Boolean) {
        updateSettingField { it.copy(spatialAudio = value) }
    }

    fun updatePreampGain(value: Float) {
        updateSettingField { it.copy(preampGain = value) }
    }

    fun updateInputGain(value: Float) {
        updateSettingField { it.copy(inputGain = value) }
    }

    fun updateOutputGain(value: Float) {
        updateSettingField { it.copy(outputGain = value) }
    }

    fun updateBalanceLeftRight(value: Float) {
        updateSettingField { it.copy(balanceLeftRight = value) }
    }

    fun updateDynamicEQ(value: Boolean) {
        updateSettingField { it.copy(dynamicEQ = value) }
    }

    fun updateThemeColor(color: String) {
        updateSettingField { it.copy(themeColor = color) }
    }

    private fun updateSettingField(mutation: (AppSettingsEntity) -> AppSettingsEntity) {
        viewModelScope.launch {
            val updated = mutation(_settings.value)
            _settings.value = updated
            repository.saveSettings(updated)
            audioEngine.updateSettings(updated, _currentGains.value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.toggleEngine(false)
    }
}

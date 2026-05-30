package com.example.engine

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.util.Log
import com.example.data.AppSettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class AudioEngine(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Default)

    private val isEngineRunning = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    // Exposed flows for the UI layer
    private val _engineActive = MutableStateFlow(false)
    val engineActive = _engineActive.asStateFlow()

    private val _playbackActive = MutableStateFlow(false)
    val playbackActive = _playbackActive.asStateFlow()

    private val _spectrumData = MutableStateFlow(FloatArray(32) { 0f })
    val spectrumData = _spectrumData.asStateFlow()

    private val _vuLeft = MutableStateFlow(0f)
    val vuLeft = _vuLeft.asStateFlow()

    private val _vuRight = MutableStateFlow(0f)
    val vuRight = _vuRight.asStateFlow()

    private val _peakLeft = MutableStateFlow(0f)
    val peakLeft = _peakLeft.asStateFlow()

    private val _peakRight = MutableStateFlow(0f)
    val peakRight = _peakRight.asStateFlow()

    private val _currentTrackInfo = MutableStateFlow("Monitoring System Audio (YouTube, Spotify, etc.)")
    val currentTrackInfo = _currentTrackInfo.asStateFlow()

    // Thread-safe parameters updated from the UI/ViewModel
    @Volatile private var settings = AppSettingsEntity()
    @Volatile private var eqGains = FloatArray(31) { 0f } // currently active eq gains

    private class SessionEffects(val sessionId: Int) {
        var equalizer: Equalizer? = null
        var bassBoost: BassBoost? = null
        var virtualizer: Virtualizer? = null

        fun release() {
            try {
                equalizer?.enabled = false
                equalizer?.release()
            } catch (e: Exception) {}
            try {
                bassBoost?.enabled = false
                bassBoost?.release()
            } catch (e: Exception) {}
            try {
                virtualizer?.enabled = false
                virtualizer?.release()
            } catch (e: Exception) {}
            equalizer = null
            bassBoost = null
            virtualizer = null
        }
    }

    private val sessionEffectsMap = java.util.concurrent.ConcurrentHashMap<Int, SessionEffects>()

    private var activeVisualizer: Visualizer? = null
    private var lastVisualizerSessionId = -1

    companion object {
        private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        @Volatile private var observer: SessionObserver? = null

        fun registerSession(sessionId: Int) {
            if (sessionId != -1) {
                sessions.add(sessionId)
                observer?.onSessionAdded(sessionId)
            }
        }

        fun unregisterSession(sessionId: Int) {
            if (sessionId != -1) {
                sessions.remove(sessionId)
                observer?.onSessionRemoved(sessionId)
            }
        }

        fun getActiveSessions(): Set<Int> = sessions

        interface SessionObserver {
            fun onSessionAdded(sessionId: Int)
            fun onSessionRemoved(sessionId: Int)
        }

        fun setObserver(obs: SessionObserver?) {
            observer = obs
        }
    }

    init {
        setObserver(object : SessionObserver {
            override fun onSessionAdded(sessionId: Int) {
                Log.d("AudioEngine", "Session added dynamically: $sessionId")
                if (isEngineRunning.get()) {
                    scope.launch {
                        attachEffectsToSession(sessionId)
                    }
                }
            }

            override fun onSessionRemoved(sessionId: Int) {
                Log.d("AudioEngine", "Session removed dynamically: $sessionId")
                scope.launch {
                    detachEffectsFromSession(sessionId)
                }
            }
        })
    }

    fun updateSettings(newSettings: AppSettingsEntity, activeGains: List<Float>) {
        this.settings = newSettings
        for (i in activeGains.indices) {
            if (i < eqGains.size) {
                eqGains[i] = activeGains[i]
            }
        }
        _currentTrackInfo.value = if (settings.engineEnabled) {
            "System Audio EQ Monitor Active"
        } else {
            "System Audio EQ Standby"
        }
        applyAllSettingsToEffects()
    }

    private fun applyAllSettingsToEffects() {
        val currentGains = eqGains
        val preamp = settings.preampGain
        val bassBoostPct = settings.bassBoost
        val spatialAudio = settings.spatialAudio
        val stereoWidenerPct = settings.stereoWidener
        val engineEnabled = settings.engineEnabled

        for (effects in sessionEffectsMap.values) {
            try {
                effects.equalizer?.let { eq ->
                    eq.enabled = engineEnabled
                    applyEqToEqualizer(eq, currentGains, preamp)
                }
            } catch (e: Exception) {
                Log.e("AudioEngine", "Error applying EQ settings to session ${effects.sessionId}: ${e.message}")
            }

            try {
                effects.bassBoost?.let { bb ->
                    bb.enabled = engineEnabled
                    applyBassBoost(bb, bassBoostPct)
                }
            } catch (e: Exception) {
                Log.e("AudioEngine", "Error applying BassBoost settings to session ${effects.sessionId}: ${e.message}")
            }

            try {
                effects.virtualizer?.let { virt ->
                    virt.enabled = spatialAudio
                    applyVirtualizer(virt, stereoWidenerPct, spatialAudio)
                }
            } catch (e: Exception) {
                Log.e("AudioEngine", "Error applying Virtualizer settings to session ${effects.sessionId}: ${e.message}")
            }
        }
    }

    private fun applyEqToEqualizer(eq: Equalizer, gains: FloatArray, preamp: Float) {
        try {
            val numBands = eq.numberOfBands.toInt()
            for (i in 0 until numBands) {
                val dbVal = if (i < gains.size) gains[i] + preamp else preamp
                val mbVal = (dbVal * 100).toInt().coerceIn(-1500, 1500)
                eq.setBandLevel(i.toShort(), mbVal.toShort())
            }
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error putting band gains to hardware EQ: ${e.message}")
        }
    }

    private fun applyBassBoost(bb: BassBoost, strengthPct: Float) {
        try {
            if (bb.strengthSupported) {
                val strengthMB = (strengthPct * 10).toInt().coerceIn(0, 1000)
                bb.setStrength(strengthMB.toShort())
            }
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error setting hardware BassBoost: ${e.message}")
        }
    }

    private fun applyVirtualizer(virt: Virtualizer, strengthPct: Float, enabled: Boolean) {
        try {
            virt.enabled = enabled
            if (enabled && virt.strengthSupported) {
                val strengthMB = (strengthPct * 10).toInt().coerceIn(0, 1000)
                virt.setStrength(strengthMB.toShort())
            }
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error setting hardware Virtualizer: ${e.message}")
        }
    }

    fun toggleEngine(active: Boolean) {
        if (active) {
            if (isEngineRunning.compareAndSet(false, true)) {
                _engineActive.value = true
                _playbackActive.value = true
                isPlaying.set(true)

                // Attach to global session 0 (system output)
                attachEffectsToSession(0)

                // Attach to any other registered dynamic sessions
                val activeSessions = getActiveSessions()
                for (id in activeSessions) {
                    if (id != 0 && id != -1) {
                        attachEffectsToSession(id)
                    }
                }

                // Start visualizer
                startMasterVisualizer()
            }
        } else {
            if (isEngineRunning.compareAndSet(true, false)) {
                _engineActive.value = false
                _playbackActive.value = false
                isPlaying.set(false)

                stopMasterVisualizer()

                // Release effects
                val keys = sessionEffectsMap.keys.toList()
                for (key in keys) {
                    detachEffectsFromSession(key)
                }
                sessionEffectsMap.clear()
            }
        }
    }

    fun togglePlay(play: Boolean) {
        if (!isEngineRunning.get()) {
            toggleEngine(true)
        }
        isPlaying.set(play)
        _playbackActive.value = play
        if (play) {
            startMasterVisualizer()
        } else {
            stopMasterVisualizer()
        }
    }

    private fun attachEffectsToSession(sessionId: Int) {
        if (sessionEffectsMap.containsKey(sessionId)) return
        Log.d("AudioEngine", "Attaching hardware audio effects to session: $sessionId")
        try {
            val effects = SessionEffects(sessionId)

            // 1. Equalizer
            try {
                val eq = Equalizer(0, sessionId)
                eq.enabled = settings.engineEnabled
                applyEqToEqualizer(eq, eqGains, settings.preampGain)
                effects.equalizer = eq
            } catch (e: Exception) {
                Log.e("AudioEngine", "Unable to load equalizer on session $sessionId: ${e.message}")
            }

            // 2. BassBoost
            try {
                val bb = BassBoost(0, sessionId)
                bb.enabled = settings.engineEnabled
                applyBassBoost(bb, settings.bassBoost)
                effects.bassBoost = bb
            } catch (e: Exception) {
                Log.e("AudioEngine", "Unable to load BassBoost on session $sessionId: ${e.message}")
            }

            // 3. Virtualizer
            try {
                val virt = Virtualizer(0, sessionId)
                virt.enabled = settings.spatialAudio
                applyVirtualizer(virt, settings.stereoWidener, settings.spatialAudio)
                effects.virtualizer = virt
            } catch (e: Exception) {
                Log.e("AudioEngine", "Unable to load Virtualizer on session $sessionId: ${e.message}")
            }

            sessionEffectsMap[sessionId] = effects

            if (activeVisualizer == null && isPlaying.get()) {
                startMasterVisualizer()
            }
        } catch (e: Exception) {
            Log.e("AudioEngine", "Failed attaching effects to session $sessionId: ${e.message}")
        }
    }

    private fun detachEffectsFromSession(sessionId: Int) {
        val effects = sessionEffectsMap.remove(sessionId)
        effects?.release()
        if (lastVisualizerSessionId == sessionId) {
            startMasterVisualizer()
        }
    }

    private fun startMasterVisualizer() {
        var success = tryStartVisualizer(0)
        if (!success) {
            val activeSessions = getActiveSessions()
            for (id in activeSessions) {
                if (id != 0 && id != -1) {
                    if (tryStartVisualizer(id)) {
                        success = true
                        break
                    }
                }
            }
        }
        if (!success) {
            Log.e("AudioEngine", "Visualizer fail fallback: starting idle display loop.")
            scope.launch {
                while (isEngineRunning.get() && activeVisualizer == null) {
                    val dummyFft = ByteArray(128) { 0 }
                    val dummyWave = ByteArray(128) { 128.toByte() }
                    processFft(dummyFft)
                    processWaveform(dummyWave)
                    Thread.sleep(100)
                }
            }
        }
    }

    private fun tryStartVisualizer(sessionId: Int): Boolean {
        try {
            if (activeVisualizer != null) {
                activeVisualizer?.enabled = false
                activeVisualizer?.release()
                activeVisualizer = null
            }
            val vis = Visualizer(sessionId)
            val captureSize = Visualizer.getCaptureSizeRange()[1]
            vis.captureSize = captureSize
            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    if (waveform != null && isEngineRunning.get()) {
                        processWaveform(waveform)
                    }
                }

                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null && isEngineRunning.get()) {
                        processFft(fft)
                    }
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, true)
            vis.enabled = true
            activeVisualizer = vis
            lastVisualizerSessionId = sessionId
            Log.d("AudioEngine", "Visualizer started successfully on session $sessionId")
            return true
        } catch (e: Exception) {
            Log.e("AudioEngine", "Failed starting visualizer on session $sessionId: ${e.message}")
            return false
        }
    }

    private fun stopMasterVisualizer() {
        try {
            activeVisualizer?.enabled = false
            activeVisualizer?.release()
        } catch (e: Exception) {}
        activeVisualizer = null
        lastVisualizerSessionId = -1
    }

    private fun processWaveform(waveform: ByteArray) {
        var sumL = 0f
        var sumR = 0f
        var maxL = 0f
        var maxR = 0f
        val len = waveform.size
        for (i in 0 until len) {
            val sample = ((waveform[i].toInt() and 0xFF) - 128) / 128f
            val absVal = abs(sample)
            if (i % 2 == 0) {
                sumL += sample * sample
                if (absVal > maxL) maxL = absVal
            } else {
                sumR += sample * sample
                if (absVal > maxR) maxR = absVal
            }
        }
        val halfLen = len / 2
        if (halfLen > 0) {
            val rmsL = sqrt(sumL / halfLen)
            val rmsR = sqrt(sumR / halfLen)
            _vuLeft.value = _vuLeft.value * 0.65f + rmsL * 0.35f
            _vuRight.value = _vuRight.value * 0.65f + rmsR * 0.35f
            _peakLeft.value = _peakLeft.value * 0.85f + maxL * 0.15f
            _peakRight.value = _peakRight.value * 0.85f + maxR * 0.15f
        }
    }

    private fun processFft(fft: ByteArray) {
        val n = fft.size
        val numBins = n / 2
        if (numBins <= 0) return
        val amplitudes = FloatArray(numBins)

        amplitudes[0] = abs(fft[0].toFloat()) / 128f
        for (i in 1 until numBins) {
            val r = fft[2 * i].toFloat()
            val im = fft[2 * i + 1].toFloat()
            amplitudes[i] = sqrt(r * r + im * im) / 128f
        }

        val barCount = 32
        val result = FloatArray(barCount)
        val maxBins = numBins.coerceAtMost(256)

        val logMin = 0.0
        val logMax = log(maxBins.toDouble(), Math.E)

        for (b in 0 until barCount) {
            val ratio = b.toFloat() / barCount
            val logVal = logMin + ratio * (logMax - logMin)
            val indexDecimal = exp(logVal)
            val indexStart = indexDecimal.toInt().coerceIn(0, numBins - 1)
            val indexEnd = ceil(indexDecimal).toInt().coerceIn(0, numBins - 1).coerceAtLeast(indexStart)

            var maxAmp = 0f
            for (i in indexStart..indexEnd) {
                if (i < numBins && amplitudes[i] > maxAmp) {
                    maxAmp = amplitudes[i]
                }
            }

            var finalAmp = maxAmp * (1.1f + (1f - ratio) * 1.6f)
            finalAmp = sqrt(finalAmp).coerceIn(0f, 1f)

            val prev = _spectrumData.value[b]
            result[b] = prev * 0.45f + finalAmp * 0.55f
        }
        _spectrumData.value = result
    }
}

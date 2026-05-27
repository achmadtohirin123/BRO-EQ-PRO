package com.example.engine

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.example.data.AppSettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import java.util.Random

class AudioEngine(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var synthJob: Job? = null

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

    private val _currentTrackInfo = MutableStateFlow("Cyber Synthwave Jam [125 BPM]")
    val currentTrackInfo = _currentTrackInfo.asStateFlow()

    // Thread-safe parameters updated from the UI/ViewModel
    @Volatile private var settings = AppSettingsEntity()
    @Volatile private var eqGains = FloatArray(31) { 0f } // currently active eq gains

    // System Audio Effects (for system wide mapping if successful)
    private var sysEqualizer: Equalizer? = null
    private var sysBassBoost: BassBoost? = null
    private var sysVirtualizer: Virtualizer? = null

    // Set of software EQ biquad filters for the synth engine (max 31 bands)
    private val leftFilters = Array(31) { BiquadFilter() }
    private val rightFilters = Array(31) { BiquadFilter() }

    // Multi-band filters for Crossover & Subwoofer
    private val subLeftFilter = BiquadFilter()
    private val subRightFilter = BiquadFilter()
    private val midHighLeftFilter = BiquadFilter()
    private val midHighRightFilter = BiquadFilter()

    // FX shelving/extra filters
    private val bassShelfLeft = BiquadFilter()
    private val bassShelfRight = BiquadFilter()
    private val trebleShelfLeft = BiquadFilter()
    private val trebleShelfRight = BiquadFilter()
    private val midPeakingLeft = BiquadFilter()
    private val midPeakingRight = BiquadFilter()

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BUFFER_SIZE_SAMPLES = 1024 // Power of 2 for FFT
    }

    init {
        setupSoftwareFilters()
    }

    fun updateSettings(newSettings: AppSettingsEntity, activeGains: List<Float>) {
        this.settings = newSettings
        for (i in activeGains.indices) {
            if (i < eqGains.size) {
                eqGains[i] = activeGains[i]
            }
        }
        updateSystemEffects()
        setupSoftwareFilters()
    }

    private fun setupSoftwareFilters() {
        val sampleRateF = SAMPLE_RATE.toFloat()
        val numBands = settings.currentBandCount

        // Define central frequencies for each band mode
        val frequencies = when (numBands) {
            7 -> floatArrayOf(60f, 150f, 400f, 1000f, 2500f, 6000f, 15000f)
            10 -> floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
            15 -> floatArrayOf(25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1620f, 2500f, 4000f, 6300f, 10000f, 16000f)
            18 -> floatArrayOf(20f, 35f, 55f, 90f, 140f, 220f, 350f, 560f, 900f, 1400f, 2200f, 3500f, 5600f, 9000f, 11000f, 14000f, 17000f, 21000f)
            31 -> floatArrayOf(
                20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 
                630f, 800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 
                10000f, 12500f, 16000f, 20000f
            )
            else -> floatArrayOf(62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f) // default 10
        }

        // Configure main EQ bands peaking biquads
        val q = when (numBands) {
            7 -> 1.0f
            10 -> 1.2f
            15 -> 1.8f
            18 -> 2.2f
            31 -> 3.5f
            else -> 1.2f
        }

        for (i in 0 until 31) {
            if (i < frequencies.size && i < eqGains.size) {
                val freq = frequencies[i]
                val gain = eqGains[i] + settings.preampGain
                leftFilters[i].configure(BiquadFilter.Type.PEAKING, freq, sampleRateF, q, gain)
                rightFilters[i].configure(BiquadFilter.Type.PEAKING, freq, sampleRateF, q, gain)
            } else {
                leftFilters[i].reset()
                rightFilters[i].reset()
            }
        }

        // Configure auxiliary FX shelving / peaking
        // Bass Booster (low shelf centered around low frequency control 40Hz to 150Hz)
        val bassFreq = settings.lowFreqControl.coerceIn(40f, 150f)
        val bassBoostDb = (settings.bassBoost / 100f) * 12f // Max 12dB boost
        bassShelfLeft.configure(BiquadFilter.Type.LOW_SHELF, bassFreq, sampleRateF, 0.7f, bassBoostDb)
        bassShelfRight.configure(BiquadFilter.Type.LOW_SHELF, bassFreq, sampleRateF, 0.7f, bassBoostDb)

        // Mid Enhancer (peaking at 1.5kHz)
        val midGainDb = (settings.midEnhancer / 100f) * 8f
        midPeakingLeft.configure(BiquadFilter.Type.PEAKING, 1500f, sampleRateF, 1.0f, midGainDb)
        midPeakingRight.configure(BiquadFilter.Type.PEAKING, 1500f, sampleRateF, 1.0f, midGainDb)

        // Treble Enhancer (high shelf centered at 8kHz)
        val trebleGainDb = (settings.trebleEnhancer / 100f) * 10f
        trebleShelfLeft.configure(BiquadFilter.Type.HIGH_SHELF, 8000f, sampleRateF, 0.7f, trebleGainDb)
        trebleShelfRight.configure(BiquadFilter.Type.HIGH_SHELF, 8000f, sampleRateF, 0.7f, trebleGainDb)

        // Subwoofer Crossover filter configurations (Lowpass for subwoofer path, Highpass for MidHighs)
        val corssoverF = settings.crossoverFreq.coerceIn(50f, 250f)
        subLeftFilter.configure(BiquadFilter.Type.LOW_PASS, corssoverF, sampleRateF, 0.7f, 0f)
        subRightFilter.configure(BiquadFilter.Type.LOW_PASS, corssoverF, sampleRateF, 0.7f, 0f)

        midHighLeftFilter.configure(BiquadFilter.Type.HIGH_PASS, corssoverF, sampleRateF, 0.7f, 0f)
        midHighRightFilter.configure(BiquadFilter.Type.HIGH_PASS, corssoverF, sampleRateF, 0.7f, 0f)
    }

    private fun updateSystemEffects() {
        if (!settings.engineEnabled) {
            sysEqualizer?.enabled = false
            sysBassBoost?.enabled = false
            sysVirtualizer?.enabled = false
            return
        }

        try {
            // Attempt to bind real-time effects to global session 0.
            // On some Android devices, this can succeed if MODIFY_AUDIO_SETTINGS is present.
            if (sysEqualizer == null) {
                sysEqualizer = Equalizer(1000, 0)
            }
            if (sysBassBoost == null) {
                sysBassBoost = BassBoost(1000, 0)
            }
            if (sysVirtualizer == null) {
                sysVirtualizer = Virtualizer(1000, 0)
            }

            sysEqualizer?.let { eq ->
                eq.enabled = true
                val numBands = eq.numberOfBands.toInt()
                for (i in 0 until numBands) {
                    // map our EQ parameters roughly to system bands
                    val level = if (i < eqGains.size) {
                        (eqGains[i] * 100).toInt().coerceIn(-1500, 1500)
                    } else {
                        0
                    }
                    eq.setBandLevel(i.toShort(), level.toShort())
                }
            }

            sysBassBoost?.let { bb ->
                bb.enabled = true
                if (bb.strengthSupported) {
                    val strength = (settings.bassBoost * 10).toInt().coerceIn(0, 1000)
                    bb.setStrength(strength.toShort())
                }
            }

            sysVirtualizer?.let { virt ->
                virt.enabled = settings.spatialAudio
                if (virt.strengthSupported) {
                    val strength = (settings.stereoWidener * 10).toInt().coerceIn(0, 1000)
                    virt.setStrength(strength.toShort())
                }
            }
        } catch (e: Exception) {
            Log.e("AudioEngine", "Unable to load system global AudioEffects (Safe Fallback to software synth DSP): ${e.message}")
        }
    }

    fun toggleEngine(active: Boolean) {
        if (active) {
            isEngineRunning.set(true)
            _engineActive.value = true
            updateSystemEffects()
            startSynthesizer()
        } else {
            isEngineRunning.set(false)
            isPlaying.set(false)
            _engineActive.value = false
            _playbackActive.value = false
            sysEqualizer?.enabled = false
            sysBassBoost?.enabled = false
            sysVirtualizer?.enabled = false
            stopSynthesizer()
        }
    }

    fun togglePlay(play: Boolean) {
        if (!isEngineRunning.get()) {
            toggleEngine(true)
        }
        isPlaying.set(play)
        _playbackActive.value = play
    }

    private fun startSynthesizer() {
        if (synthJob != null) return

        synthJob = scope.launch {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = Math.max(minBuf, BUFFER_SIZE_SAMPLES * 4)

            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            track.play()

            // State helpers inside the thread for the synthesizer loop
            var sampleIdx = 0L
            val stepLength = (60f / 125f / 4f * SAMPLE_RATE).toInt() // Length of 16th note at 125BPM
            var step = 0

            // Floating point sample arrays
            val leftBuf = FloatArray(BUFFER_SIZE_SAMPLES)
            val rightBuf = FloatArray(BUFFER_SIZE_SAMPLES)
            val outBuf = ShortArray(BUFFER_SIZE_SAMPLES * 2)

            // Multi-tap Delay variables
            val delayBufferL = FloatArray(SAMPLE_RATE * 2) // 2 second delay max
            val delayBufferR = FloatArray(SAMPLE_RATE * 2)
            var delayWriteIdx = 0

            // Reverb feedback comb-filters delay arrays
            val rDelayL1 = FloatArray(1601)
            val rDelayR1 = FloatArray(1741)
            val rDelayL2 = FloatArray(2113)
            val rDelayR2 = FloatArray(2287)
            var rdIdx1 = 0
            var rdIdx2 = 0

            // MIDI step data synthesizer patterns
            // Kick pattern: 16 steps (Triggers on 0, 4, 8, 12 - techno four-on-the-floor)
            val kickPattern = booleanArrayOf(true, false, false, false, true, false, false, false, true, false, false, false, true, false, false, false)
            // Snare-hat pattern
            val snarePattern = booleanArrayOf(false, false, true, false, false, false, true, false, false, false, true, false, false, false, true, true)
            // Bassline note pitches (freqs)
            val bassMelody = floatArrayOf(55f, 55f, 65.41f, 55f, 110f, 55f, 73.42f, 55f, 82.41f, 82.41f, 98f, 55f, 110f, 73.42f, 98f, 110f)
            // Cyber Lead Arpeggiator (16 steps)
            val leadMelody = floatArrayOf(
                220.0f, 261.63f, 329.63f, 392.0f, 440.0f, 392.0f, 329.63f, 261.63f,
                293.66f, 349.23f, 440.0f, 523.25f, 587.33f, 523.25f, 440.0f, 349.23f
            )

            // Voice Envelopes
            var kickEnv = 0f
            var snareEnv = 0f
            var bassEnv = 0f
            var leadEnv = 0f

            // Synthesizer pitch registers
            var kickPitch = 0f
            var bassFreqActive = 55f
            var leadFreqActive = 220f

            val random = Random()

            while (isEngineRunning.get()) {
                if (!isPlaying.get()) {
                    // Engine is on but paused - output pure zeros with low CPU usage
                    outBuf.fill(0)
                    track.write(outBuf, 0, outBuf.size)
                    _vuLeft.value = 0f
                    _vuRight.value = 0f
                    _peakLeft.value = 0f
                    _peakRight.value = 0f
                    Thread.sleep(15)
                    continue
                }

                // Synth DSP computation loop
                for (i in 0 until BUFFER_SIZE_SAMPLES) {
                    val stepSampleIdx = (sampleIdx % stepLength).toInt()
                    if (stepSampleIdx == 0) {
                        // Advance step sequencer (0 to 15)
                        step = ((sampleIdx / stepLength) % 16).toInt()

                        // Trigger synth notes
                        if (kickPattern[step]) {
                            kickEnv = 1.0f
                            kickPitch = 160f
                        }
                        if (snarePattern[step]) {
                            snareEnv = 0.5f
                        }
                        // Bass & Lead envelopes trigger on active steps
                        if (step % 2 == 0) {
                            bassEnv = 0.8f
                            bassFreqActive = bassMelody[step]
                        }
                        if (step % 4 != 1) {
                            leadEnv = 0.4f
                            leadFreqActive = leadMelody[step]
                        }
                    }

                    // 1. Synthesize elements
                    // A. KICK: Fast pitch decay sine wave
                    var kickSample = 0f
                    if (kickEnv > 0f) {
                        kickPitch = kickPitch * 0.9992f + 35f * 0.0008f
                        val phase = 2f * Math.PI.toFloat() * kickPitch * (stepSampleIdx.toFloat() / SAMPLE_RATE)
                        kickSample = sin(phase) * kickEnv
                        kickEnv *= 0.9985f // decay kick
                    }

                    // B. SNARE & HI-HAT: Noise modulated by envelope
                    var snareSample = 0f
                    if (snareEnv > 0f) {
                        val noise = (random.nextFloat() * 2f - 1f)
                        snareSample = noise * snareEnv
                        // Bandpass filter snared noise roughly
                        snareEnv *= 0.997f
                    }

                    // C. ACID BASS: Sawtooth / Square wave
                    var bassSample = 0f
                    if (bassEnv > 0f) {
                        val t = sampleIdx.toDouble() / SAMPLE_RATE
                        val phaseVal = (t * bassFreqActive) % 1.0f
                        // Sawtooth mathematical synth
                        val saw = (2.0 * phaseVal - 1.0).toFloat()
                        // Resonant lowpass envelope modulation
                        bassSample = saw * bassEnv
                        bassEnv *= 0.999f
                    }

                    // D. LEAD CYBER APREGGIATOR: Triangle Wave
                    var leadSample = 0f
                    if (leadEnv > 0f) {
                        val t = sampleIdx.toDouble() / SAMPLE_RATE
                        val phaseVal = (t * leadFreqActive) % 1.0f
                        // Triangle mathematical wave
                        val tri = (if (phaseVal < 0.5) 4.0 * phaseVal - 1.0 else 3.0 - 4.0 * phaseVal).toFloat()
                        leadSample = tri * leadEnv
                        // Vibrato
                        leadSample += (sin(2f * Math.PI.toFloat() * 6f * t.toFloat()) * 0.05f) * leadEnv
                        leadEnv *= 0.9982f
                    }

                    // Merge synth channels
                    // Boost kick & sub bassprogrammatically
                    val subEnhancerStrength = settings.subwooferEnhancer / 100f
                    val subSample = (kickSample + bassSample * 0.8f) * (1f + subEnhancerStrength)

                    // Basic mono synth master mix
                    var synthMix = subSample * 0.6f + snareSample * 0.25f + leadSample * 0.25f

                    // Apply pre-amp gain
                    val preampScale = Math.pow(10.0, settings.preampGain.toDouble() / 20.0).toFloat()
                    synthMix *= preampScale

                    // Crossover filter routing (Subwoofer boost vs mid/highs)
                    val subPartL = subLeftFilter.process(synthMix)
                    val midHighPartL = midHighLeftFilter.process(synthMix)

                    val subPartR = subRightFilter.process(synthMix)
                    val midHighPartR = midHighRightFilter.process(synthMix)

                    // Combine back with subwoofer balance
                    var leftCh = subPartL * (1f + subEnhancerStrength * 0.5f) + midHighPartL
                    var rightCh = subPartR * (1f + subEnhancerStrength * 0.5f) + midHighPartR

                    // 2. Applying Software DSP Rack Routing
                    _currentTrackInfo.value = if (settings.dynamicEQ) "Active Dynamic EQ Engaged" else "Studio Mixing Mode [125 BPM]"

                    // A. Equalizer cascading filters (7 to 31 bands)
                    val numBands = settings.currentBandCount
                    for (b in 0 until numBands) {
                        leftCh = leftFilters[b].process(leftCh)
                        rightCh = rightFilters[b].process(rightCh)
                    }

                    // B. Harmonic Exciter & Softeners
                    // Vocal Clarity (Peaking around 3kHz boost)
                    val vocalClar = settings.vocalClarity / 100f
                    if (vocalClar > 0f) {
                        leftCh += (midPeakingLeft.process(leftCh) * vocalClar * 0.4f)
                        rightCh += (midPeakingRight.process(rightCh) * vocalClar * 0.4f)
                    }

                    // Audio Softener (Anti-harshness low pass)
                    val softStrength = settings.audioSoftener / 100f
                    if (softStrength > 0.05f) {
                        leftCh = leftCh * (1f - softStrength * 0.3f) + subLeftFilter.process(leftCh) * (softStrength * 0.3f)
                        rightCh = rightCh * (1f - softStrength * 0.3f) + subRightFilter.process(rightCh) * (softStrength * 0.3f)
                    }

                    // Harmonic Exciter & Thickener (Add soft even harmonics)
                    val thickStrength = settings.audioThickener / 100f
                    if (thickStrength > 0f) {
                        val harmonicL = leftCh * leftCh * 0.12f * thickStrength
                        val harmonicR = rightCh * rightCh * 0.12f * thickStrength
                        leftCh += harmonicL
                        rightCh += harmonicR
                    }

                    // C. Noise reduction gating
                    val noiseGateThresh = settings.noiseReduction / 100f * 0.008f
                    if (abs(leftCh) < noiseGateThresh) leftCh *= 0.15f
                    if (abs(rightCh) < noiseGateThresh) rightCh *= 0.15f

                    // D. Bass Booster, Mid Enhancer, Treble Enhancer shelving filters
                    leftCh = bassShelfLeft.process(leftCh)
                    rightCh = bassShelfRight.process(rightCh)

                    leftCh = trebleShelfLeft.process(leftCh)
                    rightCh = trebleShelfRight.process(rightCh)

                    // E. Stereo Widening (Haas Effect Delay)
                    val widenerStrength = settings.stereoWidener / 100f
                    val extremeStrength = settings.extremeStereo / 100f

                    if (widenerStrength > 0f) {
                        val delayOffset = (220 + widenerStrength * 800).toInt() // 5ms to 22ms
                        val prevIdx = (delayWriteIdx - delayOffset + delayBufferR.size) % delayBufferR.size
                        // Feed right channel slightly delayed
                        rightCh = rightCh * (1f - widenerStrength * 0.4f) + delayBufferR[prevIdx] * (widenerStrength * 0.4f)
                    }

                    if (extremeStrength > 0f) {
                        // Phase-inverted crosstalk for massive spatial stereo expansion
                        val tmpL = leftCh - rightCh * extremeStrength * 0.45f
                        val tmpR = rightCh - leftCh * extremeStrength * 0.45f
                        leftCh = tmpL
                        rightCh = tmpR
                    }

                    // F. Digital Delay Feedback Line
                    val dFeedback = settings.delayFeedback / 100f * 0.7f
                    val dMs = settings.delayMs
                    val dSamples = (dMs / 1000f * SAMPLE_RATE).toInt().coerceIn(100, delayBufferL.size - 1)

                    val delayReadIdx = (delayWriteIdx - dSamples + delayBufferL.size) % delayBufferL.size
                    val delayedL = delayBufferL[delayReadIdx]
                    val delayedR = delayBufferR[delayReadIdx]

                    // Write back to delay lines with feedback
                    delayBufferL[delayWriteIdx] = leftCh + delayedL * dFeedback
                    delayBufferR[delayWriteIdx] = rightCh + delayedR * dFeedback
                    delayWriteIdx = (delayWriteIdx + 1) % delayBufferL.size

                    // Mix in delayed components
                    leftCh += (delayedL * 0.35f)
                    rightCh += (delayedR * 0.35f)

                    // G. Environmental Reverb (Comb-feedback filters)
                    val rLevel = settings.reverbLevel / 100f * 0.4f
                    if (rLevel > 0f) {
                        val combL1 = rDelayL1[rdIdx1]
                        val combR1 = rDelayR1[rdIdx1]
                        val combL2 = rDelayL2[rdIdx2]
                        val combR2 = rDelayR2[rdIdx2]

                        rDelayL1[rdIdx1] = leftCh + combL1 * 0.75f
                        rDelayR1[rdIdx1] = rightCh + combR1 * 0.73f
                        rDelayL2[rdIdx2] = leftCh + combL2 * 0.68f
                        rDelayR2[rdIdx2] = rightCh + combR2 * 0.70f

                        rdIdx1 = (rdIdx1 + 1) % rDelayL1.size
                        rdIdx2 = (rdIdx2 + 1) % rDelayL2.size

                        leftCh += (combL1 * 0.2f + combL2 * 0.15f) * rLevel
                        rightCh += (combR1 * 0.2f + combR2 * 0.15f) * rLevel
                    }

                    // H. Analog Saturation & Tube Warmth (Non-linear Softclipping)
                    val tube = (settings.tubeWarmth / 100f) * 1.5f
                    val saturation = (settings.analogSaturation / 100f) * 1.8f

                    if (saturation > 0f) {
                        // Wave-shaping formula: tanh simulation
                        val drive = 1f + saturation
                        leftCh = tanh(leftCh * drive) / (1f + saturation * 0.2f)
                        rightCh = tanh(rightCh * drive) / (1f + saturation * 0.2f)
                    }

                    if (tube > 0f) {
                        // Asymmetric wave folding warmth
                        leftCh = if (leftCh > 0) leftCh * (1f - tube * 0.15f) else leftCh * (1f + tube * 0.2f)
                        rightCh = if (rightCh > 0) rightCh * (1f - tube * 0.15f) else rightCh * (1f + tube * 0.2f)
                    }

                    // I. Dynamics Processing: Compressor & Brickwall Limiter
                    val threshLinear = Math.pow(10.0, settings.compressorThreshold.toDouble() / 20.0).toFloat()
                    val ratio = settings.compressorRatio
                    val envelopeL = abs(leftCh)
                    val envelopeR = abs(rightCh)

                    if (envelopeL > threshLinear) {
                        val excess = envelopeL - threshLinear
                        leftCh = leftCh * (threshLinear + excess / ratio) / envelopeL
                    }
                    if (envelopeR > threshLinear) {
                        val excess = envelopeR - threshLinear
                        rightCh = rightCh * (threshLinear + excess / ratio) / envelopeR
                    }

                    // Limit ultimate loudness
                    val loudMax = 1.0f + (settings.loudnessMaximizer / 100f) * 0.5f
                    leftCh *= loudMax
                    rightCh *= loudMax

                    val limitLinear = Math.pow(10.0, settings.limiterThreshold.toDouble() / 20.0).toFloat()
                    leftCh = leftCh.coerceIn(-limitLinear, limitLinear)
                    rightCh = rightCh.coerceIn(-limitLinear, limitLinear)

                    // J. Balance Left / Right
                    val bal = settings.balanceLeftRight / 50f // -1.0 to 1.0
                    if (bal > 0) {
                        leftCh *= (1f - bal)
                    } else if (bal < 0) {
                        rightCh *= (1f + bal)
                    }

                    // K. Final Output Gain Slider
                    val outGainScale = Math.pow(10.0, settings.outputGain.toDouble() / 20.0).toFloat()
                    leftCh *= outGainScale
                    rightCh *= outGainScale

                    // Store samples for UI Spectrum analyzer
                    leftBuf[i] = leftCh
                    rightBuf[i] = rightCh

                    // Convert to Float, saturate, and scale to 16bit Short PCM
                    val shortValL = (leftCh.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    val shortValR = (rightCh.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

                    outBuf[i * 2] = shortValL
                    outBuf[i * 2 + 1] = shortValR

                    sampleIdx++
                }

                // Write the buffer to physical hardware
                track.write(outBuf, 0, outBuf.size)

                // 3. Compute Real FFT and VU Meters
                // Logarithmic frequency spectrum calculation for 32 visualization bars
                val fftAmps = FftAnalyzer.computeFrequencies(leftBuf, SAMPLE_RATE, 32)
                _spectrumData.value = fftAmps

                // VU Stereo Meters calculation
                var rmsL = 0f
                var rmsR = 0f
                var pkL = 0f
                var pkR = 0f
                for (i in leftBuf.indices) {
                    val lAbs = abs(leftBuf[i])
                    val rAbs = abs(rightBuf[i])
                    rmsL += lAbs * lAbs
                    rmsR += rAbs * rAbs
                    if (lAbs > pkL) pkL = lAbs
                    if (rAbs > pkR) pkR = rAbs
                }
                rmsL = sqrt(rmsL / BUFFER_SIZE_SAMPLES)
                rmsR = sqrt(rmsR / BUFFER_SIZE_SAMPLES)

                // Smoothed exponential level monitoring
                _vuLeft.value = _vuLeft.value * 0.7f + rmsL * 0.3f
                _vuRight.value = _vuRight.value * 0.7f + rmsR * 0.3f
                _peakLeft.value = _peakLeft.value * 0.92f + pkL * 0.08f
                _peakRight.value = _peakRight.value * 0.92f + pkR * 0.08f
            }

            track.stop()
            track.release()
        }
    }

    private fun stopSynthesizer() {
        synthJob?.cancel()
        synthJob = null
    }

    // Mathematical Hyperbolic Tangent helper
    private fun tanh(x: Float): Float {
        val e2 = exp(2.0 * x)
        return ((e2 - 1) / (e2 + 1)).toFloat()
    }
}

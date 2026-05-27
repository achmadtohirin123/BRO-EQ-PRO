package com.example.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object FftAnalyzer {
    // Computes the in-place Radix-2 Cooley-Tukey FFT. n must be a power of 2.
    fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        if (n <= 1) return

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR

                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // Cooley-Tukey decimation-in-time
        var size = 2
        while (size <= n) {
            val halfSize = size shr 1
            val tablestep = n / size
            for (i in 0 until n step size) {
                for (k in 0 until halfSize) {
                    val angle = -2.0 * Math.PI * k / size
                    val wr = cos(angle).toFloat()
                    val wi = sin(angle).toFloat()

                    val tIndex = i + k + halfSize
                    val tr = real[tIndex] * wr - imag[tIndex] * wi
                    val ti = real[tIndex] * wi + imag[tIndex] * wr

                    real[tIndex] = real[i + k] - tr
                    imag[tIndex] = imag[i + k] - ti

                    real[i + k] += tr
                    imag[i + k] += ti
                }
            }
            size = size shl 1
        }
    }

    // Returns a smoothed list of amplitude bins mapped to visualizer frequency bars
    fun computeFrequencies(samples: FloatArray, samplingRate: Int, barCount: Int): FloatArray {
        val n = samples.size
        // Ensure size is a power of 2
        var m = 2
        while (m < n) {
            m = m shl 1
        }
        val pR = FloatArray(m)
        val pI = FloatArray(m)
        System.arraycopy(samples, 0, pR, 0, samples.size.coerceAtMost(m))

        // Run FFT
        fft(pR, pI)

        // Compute amplitudes for first half of frequencies (0 to Nyquist)
        val maxBins = m / 2
        val amplitudes = FloatArray(maxBins)
        for (i in 0 until maxBins) {
            amplitudes[i] = sqrt(pR[i] * pR[i] + pI[i] * pI[i]) / (m / 2)
        }

        // Logarithmically distribute amplitudes to target visualizer bars
        val result = FloatArray(barCount)
        val logMin = 0.0
        val logMax = Math.log(maxBins.toDouble())

        for (b in 0 until barCount) {
            val ratio = b.toFloat() / barCount
            val logVal = logMin + ratio * (logMax - logMin)
            val indexDecimal = Math.exp(logVal)
            val indexStart = indexDecimal.toInt().coerceIn(0, maxBins - 1)
            val indexEnd = Math.ceil(indexDecimal).toInt().coerceIn(0, maxBins - 1)

            var maxAmp = 0f
            for (i in indexStart..indexEnd) {
                if (amplitudes[i] > maxAmp) {
                    maxAmp = amplitudes[i]
                }
            }
            // Add slight compression & gain for visualization impact
            result[b] = Math.min(1.0f, (sqrt(maxAmp) * 2.5f))
        }
        return result
    }
}

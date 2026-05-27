package com.example.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

class BiquadFilter {
    enum class Type {
        PEAKING,
        LOW_PASS,
        HIGH_PASS,
        LOW_SHELF,
        HIGH_SHELF
    }

    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    private var a0 = 1f
    private var a1 = 0f
    private var a2 = 0f
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f

    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }

    fun configure(type: Type, frequency: Float, sampleRate: Float, q: Float, gainDb: Float) {
        val w0 = (2f * Math.PI * frequency / sampleRate).toFloat()
        val alpha = (sin(w0) / (2f * q)).toFloat()
        val a = sqrt(Math.pow(10.0, gainDb.toDouble() / 20.0)).toFloat()

        when (type) {
            Type.PEAKING -> {
                b0 = 1f + alpha * a
                b1 = -2f * cos(w0)
                b2 = 1f - alpha * a
                a0 = 1f + alpha / a
                a1 = -2f * cos(w0)
                a2 = 1f - alpha / a
            }
            Type.LOW_PASS -> {
                b0 = (1f - cos(w0)) / 2f
                b1 = 1f - cos(w0)
                b2 = (1f - cos(w0)) / 2f
                a0 = 1f + alpha
                a1 = -2f * cos(w0)
                a2 = 1f - alpha
            }
            Type.HIGH_PASS -> {
                b0 = (1f + cos(w0)) / 2f
                b1 = -(1f + cos(w0))
                b2 = (1f + cos(w0)) / 2f
                a0 = 1f + alpha
                a1 = -2f * cos(w0)
                a2 = 1f - alpha
            }
            Type.LOW_SHELF -> {
                val s = 1f // slope tuning
                val aPlus1 = a + 1f
                val aMinus1 = a - 1f
                val cosW0 = cos(w0)
                val beta = (sin(w0) * sqrt((a * a + 1f) * (1f / s - 1f) + 2f * a)).toFloat()

                b0 = a * (aPlus1 - aMinus1 * cosW0 + beta)
                b1 = 2f * a * (aMinus1 - aPlus1 * cosW0)
                b2 = a * (aPlus1 - aMinus1 * cosW0 - beta)
                a0 = aPlus1 + aMinus1 * cosW0 + beta
                a1 = -2f * (aMinus1 + aPlus1 * cosW0)
                a2 = aPlus1 + aMinus1 * cosW0 - beta
            }
            Type.HIGH_SHELF -> {
                val s = 1f
                val aPlus1 = a + 1f
                val aMinus1 = a - 1f
                val cosW0 = cos(w0)
                val beta = (sin(w0) * sqrt((a * a + 1f) * (1f / s - 1f) + 2f * a)).toFloat()

                b0 = a * (aPlus1 + aMinus1 * cosW0 + beta)
                b1 = -2f * a * (aMinus1 + aPlus1 * cosW0)
                b2 = a * (aPlus1 + aMinus1 * cosW0 - beta)
                a0 = aPlus1 - aMinus1 * cosW0 + beta
                a1 = 2f * (aMinus1 - aPlus1 * cosW0)
                a2 = aPlus1 - aMinus1 * cosW0 - beta
            }
        }

        // Normalize coefficients
        b0 /= a0
        b1 /= a0
        b2 /= a0
        a1 /= a0
        a2 /= a0
    }

    fun process(sample: Float): Float {
        val out = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = sample
        y2 = y1
        y1 = out
        return out
    }
}

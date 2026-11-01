package vibe.tube.data.audio.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object EqFilterMath {
    const val COEFFICIENTS_PER_SECTION = 5
    const val REFERENCE_SAMPLE_RATE = 48_000

    private const val NYQUIST_MARGIN = 0.49
    private const val RESPONSE_POINTS = 256

    fun isBypassed(
        band: EqBand,
        sampleRate: Int,
    ): Boolean = !band.enabled || (band.type.hasGain && band.gain == 0.0) || band.frequency >= sampleRate * NYQUIST_MARGIN

    fun design(
        band: EqBand,
        sampleRate: Int,
        out: DoubleArray,
        offset: Int,
    ) {
        val w = 2.0 * PI * band.frequency / sampleRate
        val cosW = cos(w)
        val alpha = sin(w) / (2.0 * band.q)
        val a = 10.0.pow(band.gain / 40.0)
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        when (band.type) {
            EqFilterType.PEAK -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosW
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosW
                a2 = 1.0 - alpha / a
            }

            EqFilterType.LOW_SHELF -> {
                val k = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * cosW + k)
                b1 = 2.0 * a * ((a - 1) - (a + 1) * cosW)
                b2 = a * ((a + 1) - (a - 1) * cosW - k)
                a0 = (a + 1) + (a - 1) * cosW + k
                a1 = -2.0 * ((a - 1) + (a + 1) * cosW)
                a2 = (a + 1) + (a - 1) * cosW - k
            }

            EqFilterType.HIGH_SHELF -> {
                val k = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * cosW + k)
                b1 = -2.0 * a * ((a - 1) + (a + 1) * cosW)
                b2 = a * ((a + 1) + (a - 1) * cosW - k)
                a0 = (a + 1) - (a - 1) * cosW + k
                a1 = 2.0 * ((a - 1) - (a + 1) * cosW)
                a2 = (a + 1) - (a - 1) * cosW - k
            }

            EqFilterType.LOW_PASS -> {
                b0 = (1.0 - cosW) / 2.0
                b1 = 1.0 - cosW
                b2 = (1.0 - cosW) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosW
                a2 = 1.0 - alpha
            }

            EqFilterType.HIGH_PASS -> {
                b0 = (1.0 + cosW) / 2.0
                b1 = -(1.0 + cosW)
                b2 = (1.0 + cosW) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosW
                a2 = 1.0 - alpha
            }
        }
        out[offset] = b0 / a0
        out[offset + 1] = b1 / a0
        out[offset + 2] = b2 / a0
        out[offset + 3] = a1 / a0
        out[offset + 4] = a2 / a0
    }

    fun magnitudeDb(
        bands: List<EqBand>,
        frequency: Double,
        sampleRate: Int = REFERENCE_SAMPLE_RATE,
    ): Double {
        val coefficients = DoubleArray(COEFFICIENTS_PER_SECTION)
        val w = 2.0 * PI * frequency / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2.0 * w)
        val s2 = sin(2.0 * w)
        var total = 0.0
        for (band in bands) {
            if (isBypassed(band, sampleRate)) continue
            design(band, sampleRate, coefficients, 0)
            val nr = coefficients[0] + coefficients[1] * c1 + coefficients[2] * c2
            val ni = -(coefficients[1] * s1 + coefficients[2] * s2)
            val dr = 1.0 + coefficients[3] * c1 + coefficients[4] * c2
            val di = -(coefficients[3] * s1 + coefficients[4] * s2)
            total += 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }
        return total
    }

    fun peakDb(bands: List<EqBand>): Double {
        if (bands.none { !isBypassed(it, REFERENCE_SAMPLE_RATE) }) return 0.0
        var peak = Double.NEGATIVE_INFINITY
        for (i in 0..RESPONSE_POINTS) {
            peak = max(peak, magnitudeDb(bands, frequencyAt(i.toDouble() / RESPONSE_POINTS)))
        }
        return peak
    }

    fun autoPreampDb(bands: List<EqBand>): Double {
        val peak = peakDb(bands)
        return if (peak > 0.0) -peak else 0.0
    }

    fun frequencyAt(fraction: Double): Double =
        EqLimits.MIN_FREQUENCY * (EqLimits.MAX_FREQUENCY / EqLimits.MIN_FREQUENCY).pow(fraction.coerceIn(0.0, 1.0))

    fun fractionOf(frequency: Double): Double =
        (
            log10(frequency.coerceIn(EqLimits.MIN_FREQUENCY, EqLimits.MAX_FREQUENCY) / EqLimits.MIN_FREQUENCY) /
                log10(EqLimits.MAX_FREQUENCY / EqLimits.MIN_FREQUENCY)
        )
}

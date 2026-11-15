package org.voicelab.android

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * First native Android DSP layer.
 *
 * This decoder intentionally accepts uncompressed PCM WAV only. Compressed formats
 * are handled by the Android media decoder in a later integration step.
 */
data class AudioBuffer(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int
) {
    val durationSeconds: Double
        get() = samples.size.toDouble() / sampleRate
}

data class BasicVoiceMetrics(
    val durationSeconds: Double,
    val sampleRateHz: Int,
    val channels: Int,
    val rms: Double,
    val intensityDbfs: Double,
    val zeroCrossingRate: Double,
    val pitchMeanHz: Double?,
    val pitchMinHz: Double?,
    val pitchMaxHz: Double?,
    val pitchStdHz: Double?
)

object WavPcmDecoder {
    fun decode(bytes: ByteArray): AudioBuffer {
        require(bytes.size >= 44) { "File is too small to be a WAV file." }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        fun readFourCc(): String = CharArray(4) { input.readByte().toInt().toChar() }.concatToString()
        fun readU16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        fun readU32(): Long = (readU16().toLong() or (readU16().toLong() shl 16))

        require(readFourCc() == "RIFF") { "Unsupported audio format: expected RIFF WAV." }
        readU32()
        require(readFourCc() == "WAVE") { "Invalid WAV header." }

        var format = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var pcmData: ByteArray? = null

        while (input.available() >= 8) {
            val chunkId = readFourCc()
            val chunkSize = readU32().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            require(chunkSize <= input.available()) { "Corrupt WAV chunk." }
            val chunk = ByteArray(chunkSize)
            input.readFully(chunk)
            if (chunkId == "fmt ") {
                val fmt = DataInputStream(ByteArrayInputStream(chunk))
                format = fmt.readUnsignedByte() or (fmt.readUnsignedByte() shl 8)
                channels = fmt.readUnsignedByte() or (fmt.readUnsignedByte() shl 8)
                sampleRate = (fmt.readUnsignedByte() or (fmt.readUnsignedByte() shl 8) or
                    (fmt.readUnsignedByte() shl 16) or (fmt.readUnsignedByte() shl 24))
                bitsPerSample = (chunk[14].toInt() and 0xff) or ((chunk[15].toInt() and 0xff) shl 8)
            } else if (chunkId == "data") {
                pcmData = chunk
            }
            if (chunkSize % 2 == 1 && input.available() > 0) input.readByte()
        }

        require(format == 1) { "Only uncompressed PCM WAV is supported at this stage." }
        require(channels in 1..8 && sampleRate > 0) { "Invalid WAV audio format." }
        require(bitsPerSample == 8 || bitsPerSample == 16 ||
            bitsPerSample == 24 || bitsPerSample == 32) { "Unsupported PCM bit depth: $bitsPerSample." }
        val data = requireNotNull(pcmData) { "WAV file has no audio data." }
        val bytesPerSample = bitsPerSample / 8
        val frameSize = bytesPerSample * channels
        require(frameSize > 0 && data.size >= frameSize) { "WAV contains no complete audio frames." }

        val frames = data.size / frameSize
        val mono = FloatArray(frames)
        var offset = 0
        for (frame in 0 until frames) {
            var sum = 0.0
            repeat(channels) {
                val value = when (bitsPerSample) {
                    8 -> ((data[offset].toInt() and 0xff) - 128) / 128.0
                    16 -> {
                        val raw = (data[offset].toInt() and 0xff) or (data[offset + 1].toInt() shl 8)
                        raw.toShort().toDouble() / 32768.0
                    }
                    24 -> {
                        var raw = (data[offset].toInt() and 0xff) or
                            ((data[offset + 1].toInt() and 0xff) shl 8) or
                            ((data[offset + 2].toInt() and 0xff) shl 16)
                        if ((raw and 0x800000) != 0) raw = raw or -0x1000000
                        raw.toDouble() / 8388608.0
                    }
                    else -> {
                        val raw = (data[offset].toInt() and 0xff) or
                            ((data[offset + 1].toInt() and 0xff) shl 8) or
                            ((data[offset + 2].toInt() and 0xff) shl 16) or
                            (data[offset + 3].toInt() shl 24)
                        raw.toDouble() / 2147483648.0
                    }
                }
                sum += value
                offset += bytesPerSample
            }
            mono[frame] = (sum / channels).toFloat()
        }
        return AudioBuffer(mono, sampleRate, channels)
    }
}

object VoiceAnalyzer {
    fun analyze(audio: AudioBuffer, minPitchHz: Double = 60.0, maxPitchHz: Double = 500.0): BasicVoiceMetrics {
        val x = audio.samples
        require(x.isNotEmpty()) { "Audio is empty." }
        val mean = x.average()
        var energy = 0.0
        var crossings = 0
        for (i in x.indices) {
            val centered = x[i] - mean.toFloat()
            energy += centered * centered
            if (i > 0 && (x[i - 1] >= 0f) != (x[i] >= 0f)) crossings++
        }
        val rms = sqrt(energy / x.size)
        val pitch = estimatePitchTrack(x, audio.sampleRate, minPitchHz, maxPitchHz)
        val sorted = pitch.sorted()
        val pitchMean = pitch.takeIf { it.isNotEmpty() }?.average()
        val pitchStd = if (pitchMean != null) sqrt(pitch.map { (it - pitchMean) * (it - pitchMean) }.average()) else null
        return BasicVoiceMetrics(
            durationSeconds = audio.durationSeconds,
            sampleRateHz = audio.sampleRate,
            channels = audio.channels,
            rms = rms,
            intensityDbfs = 20.0 * log10(max(rms, 1e-12)),
            zeroCrossingRate = crossings.toDouble() / max(1, x.size - 1),
            pitchMeanHz = pitchMean,
            pitchMinHz = sorted.firstOrNull(),
            pitchMaxHz = sorted.lastOrNull(),
            pitchStdHz = pitchStd
        )
    }

    private fun estimatePitchTrack(x: FloatArray, sampleRate: Int, minHz: Double, maxHz: Double): List<Double> {
        require(minHz > 0 && maxHz > minHz) { "Invalid pitch range." }
        val window = min(x.size, max(1024, sampleRate / 25))
        if (window < 256) return emptyList()
        val hop = max(1, window / 2)
        val minLag = max(2, (sampleRate / maxHz).toInt())
        val maxLag = min(window / 2, (sampleRate / minHz).toInt())
        if (maxLag <= minLag) return emptyList()
        val result = mutableListOf<Double>()
        var start = 0
        while (start + window <= x.size) {
            var mean = 0.0
            for (i in 0 until window) mean += x[start + i]
            mean /= window
            var bestLag = -1
            var bestCorrelation = 0.0
            for (lag in minLag..maxLag) {
                var cross = 0.0
                var left = 0.0
                var right = 0.0
                for (i in 0 until window - lag) {
                    val a = x[start + i] - mean.toFloat()
                    val b = x[start + i + lag] - mean.toFloat()
                    cross += a * b
                    left += a * a
                    right += b * b
                }
                val correlation = if (left > 0.0 && right > 0.0) cross / sqrt(left * right) else 0.0
                if (correlation > bestCorrelation) {
                    bestCorrelation = correlation
                    bestLag = lag
                }
            }
            if (bestLag > 0 && bestCorrelation >= 0.6) result += sampleRate.toDouble() / bestLag
            start += hop
        }
        return result
    }
}

package com.example.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.Executors
import kotlin.math.sin

/**
 * Lightweight real-time procedural audio engine and haptic feedback provider.
 * Requires zero external audio assets; synthesizes tactical sound effects dynamically.
 */
class GameAudio(val context: Context? = null) {

    private val executor = Executors.newFixedThreadPool(2)
    private val sampleRate = 22050

    private val vibrator: Vibrator? by lazy {
        context?.let { ctx ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }

    private fun playPcm(samples: ShortArray) {
        executor.execute {
            try {
                val bufferSize = samples.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep((samples.size * 1000L / sampleRate) + 50)
                track.release()
            } catch (ignored: Exception) {
            }
        }
    }

    fun playSuppressedShot() {
        val durationMs = 120
        val numSamples = (sampleRate * (durationMs / 1000f)).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val env = 1.0f - (i.toFloat() / numSamples)
            val noise = (Math.random() * 2.0 - 1.0).toFloat()
            val pop = sin(2.0 * Math.PI * 180.0 * i / sampleRate).toFloat()
            samples[i] = ((noise * 0.7f + pop * 0.3f) * env * env * 22000).toInt().toShort()
        }
        playPcm(samples)
    }

    fun playHeavyShot() {
        val durationMs = 280
        val numSamples = (sampleRate * (durationMs / 1000f)).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val env = 1.0f - (i.toFloat() / numSamples)
            val freq = 120.0 - 60.0 * (i.toFloat() / numSamples)
            val tone = sin(2.0 * Math.PI * freq * i / sampleRate).toFloat()
            val noise = (Math.random() * 2.0 - 1.0).toFloat()
            samples[i] = ((tone * 0.6f + noise * 0.4f) * env * 28000).toInt().toShort()
        }
        playPcm(samples)
        vibrate(60)
    }

    fun playTakedown() {
        val durationMs = 180
        val numSamples = (sampleRate * (durationMs / 1000f)).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val env = 1.0f - (i.toFloat() / numSamples)
            val freq = 80.0 - 30.0 * (i.toFloat() / numSamples)
            val punch = sin(2.0 * Math.PI * freq * i / sampleRate).toFloat()
            samples[i] = (punch * env * env * 24000).toInt().toShort()
        }
        playPcm(samples)
        vibrate(80)
    }

    fun playTerminalBeep(success: Boolean) {
        val durationMs = if (success) 140 else 220
        val numSamples = (sampleRate * (durationMs / 1000f)).toInt()
        val samples = ShortArray(numSamples)
        val freq = if (success) 880.0 else 220.0
        for (i in 0 until numSamples) {
            val env = 1.0f - (i.toFloat() / numSamples)
            val tone = sin(2.0 * Math.PI * freq * i / sampleRate).toFloat()
            samples[i] = (tone * env * 18000).toInt().toShort()
        }
        playPcm(samples)
    }

    fun playBreachExplosion() {
        val durationMs = 450
        val numSamples = (sampleRate * (durationMs / 1000f)).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val env = 1.0f - (i.toFloat() / numSamples)
            val noise = (Math.random() * 2.0 - 1.0).toFloat()
            val sub = sin(2.0 * Math.PI * 55.0 * i / sampleRate).toFloat()
            samples[i] = ((noise * 0.7f + sub * 0.5f) * env * 30000).toInt().toShort()
        }
        playPcm(samples)
        vibrate(200)
    }

    fun playEmpBlast() {
        val durationMs = 320
        val numSamples = (sampleRate * (durationMs / 1000f)).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val env = 1.0f - (i.toFloat() / numSamples)
            val freq = 1200.0 - 900.0 * (i.toFloat() / numSamples)
            val tone = sin(2.0 * Math.PI * freq * i / sampleRate).toFloat()
            samples[i] = (tone * env * 22000).toInt().toShort()
        }
        playPcm(samples)
    }

    fun playAlarm() {
        val durationMs = 250
        val numSamples = (sampleRate * (durationMs / 1000f)).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val tone = sin(2.0 * Math.PI * 650.0 * i / sampleRate).toFloat()
            samples[i] = (tone * 16000).toInt().toShort()
        }
        playPcm(samples)
        vibrate(100)
    }

    fun vibrate(durationMs: Long) {
        try {
            vibrator?.let { vib ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(durationMs)
                }
            }
        } catch (ignored: Exception) {
        }
    }

    fun release() {
        executor.shutdown()
    }
}

package com.example.echopath.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class RadarTelemetry(
    val isRunning: Boolean,
    val isSimulated: Boolean,
    val carrierFreq: Int,
    val tier: String, // "CLEAR", "CAUTION", "CRITICAL"
    val delta: Double,
    val baseline: Double,
    val carrierPower: Double,
    val sidebandEnergy: Double,
    val estimatedDistanceMeters: Double
)

interface RadarUpdateListener {
    fun onRadarUpdate(telemetry: RadarTelemetry)
}

class AcousticRadarEngine(private val context: Context) {
    
    private var listener: RadarUpdateListener? = null
    
    @Volatile var isRunning = false
    @Volatile var isSimulated = false
    
    var carrierFreq = 18500
    var sensitivityThreshold = 6.5
    var soundEnabled = true
    var hapticsEnabled = true
    
    private var tier = "CLEAR"
    private var delta = 0.0
    private var baseline = 0.0
    private var carrierPower = 0.0
    private var sidebandEnergy = 0.0
    private var estimatedDistanceMeters = 2.0

    private val sampleRate = 48000
    private val fftSize = 2048
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    private var recordThread: Thread? = null
    
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    
    private var lastTier1Time = 0L
    private var lastTier2Time = 0L

    fun setUpdateListener(listener: RadarUpdateListener) {
        this.listener = listener
    }

    @SuppressLint("MissingPermission")
    fun start(freq: Int = 18500, sensitivity: Double = 6.5, soundEnabled: Boolean = true, forceSimulation: Boolean = false) {
        if (isRunning) return
        
        this.carrierFreq = freq
        this.sensitivityThreshold = sensitivity
        this.soundEnabled = soundEnabled
        this.isSimulated = forceSimulation
        this.isRunning = true
        this.baseline = 0.0
        
        if (isSimulated) {
            startSimulationThread()
            return
        }

        try {
            val minBufferSizeTrack = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioTrack = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBufferSizeTrack, sampleRate * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val toneBuffer = ShortArray(sampleRate)
            for (i in toneBuffer.indices) {
                val envelope = if (i < sampleRate * 0.1) i / (sampleRate * 0.1) else 1.0
                val sample = sin(2.0 * Math.PI * i / (sampleRate / carrierFreq.toDouble()))
                toneBuffer[i] = (sample * 32767.0 * 0.85 * envelope).toInt().toShort()
            }
            
            audioTrack?.play()
            Thread {
                while (isRunning && !isSimulated) {
                    audioTrack?.write(toneBuffer, 0, toneBuffer.size)
                }
            }.start()

            val minBufferSizeRecord = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBufferSizeRecord, fftSize * 4)
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBufferSizeRecord, fftSize * 4)
                )
            }

            audioRecord?.startRecording()
            
            startDspThread()

        } catch (e: Exception) {
            e.printStackTrace()
            stopHardware()
            this.isSimulated = true
            startSimulationThread()
        }
    }

    private fun startDspThread() {
        recordThread = Thread {
            val audioData = ShortArray(fftSize)
            val real = DoubleArray(fftSize)
            val imag = DoubleArray(fftSize)
            val magnitudes = DoubleArray(fftSize / 2)
            val fft = FFT(fftSize)

            while (isRunning && !isSimulated) {
                val readSize = audioRecord?.read(audioData, 0, fftSize) ?: 0
                if (readSize == fftSize) {
                    for (i in 0 until fftSize) {
                        val window = 0.5 * (1 - cos(2.0 * Math.PI * i / (fftSize - 1)))
                        real[i] = audioData[i] * window
                        imag[i] = 0.0
                    }

                    fft.process(real, imag)

                    for (i in magnitudes.indices) {
                        magnitudes[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
                    }

                    processMagnitudes(magnitudes)
                }
            }
        }
        recordThread?.start()
    }
    
    private fun startSimulationThread() {
        recordThread = Thread {
            var simDistance = 2.0
            var simDirection = -1.0
            
            while (isRunning && isSimulated) {
                simDistance += simDirection * (0.016 * 1.5)
                if (simDistance <= 0.4) {
                    simDistance = 0.4
                    simDirection = 1.0
                } else if (simDistance >= 2.0) {
                    simDistance = 2.0
                    simDirection = -1.0
                }
                
                estimatedDistanceMeters = simDistance
                
                carrierPower = 200.0 + Math.random() * 20.0
                val simBaseline = 30.0
                baseline = baseline * 0.93 + simBaseline * 0.07
                
                if (simDirection < 0) {
                    val proximityFactor = max(0.0, (2.0 - simDistance) / 1.6)
                    delta = proximityFactor * sensitivityThreshold * 2.5 + (Math.random() * 2.0)
                } else {
                    delta = Math.random() * 2.0
                }
                
                sidebandEnergy = baseline + delta
                
                evaluateHazards()
                Thread.sleep(16)
            }
        }
        recordThread?.start()
    }

    private fun processMagnitudes(magnitudes: DoubleArray) {
        val binResolution = sampleRate.toDouble() / fftSize
        val targetBin = Math.round(carrierFreq / binResolution).toInt()
        
        carrierPower = if (targetBin < magnitudes.size) magnitudes[targetBin] else 0.0
        
        var sidebandSum = 0.0
        var count = 0
        for (i in (targetBin + 2)..(targetBin + 14)) {
            if (i < magnitudes.size) {
                sidebandSum += magnitudes[i]
                count++
            }
        }
        
        sidebandEnergy = if (count > 0) sidebandSum / count else 0.0
        
        baseline = baseline * 0.93 + sidebandEnergy * 0.07
        delta = max(0.0, sidebandEnergy - baseline)
        
        evaluateHazards()
    }

    private fun evaluateHazards() {
        val now = System.currentTimeMillis()
        
        if (delta >= sensitivityThreshold * 2.2) {
            tier = "CRITICAL"
            if (now - lastTier2Time > 400) {
                lastTier2Time = now
                triggerHaptic(longArrayOf(0, 140, 40, 140))
                if (soundEnabled) playUrgentTick()
            }
        } else if (delta >= sensitivityThreshold) {
            tier = "CAUTION"
            if (now - lastTier1Time > 600) {
                lastTier1Time = now
                triggerHaptic(longArrayOf(0, 70))
                if (soundEnabled) playSubtleTick()
            }
        } else {
            tier = "CLEAR"
        }
        
        listener?.onRadarUpdate(
            RadarTelemetry(
                isRunning, isSimulated, carrierFreq, tier, delta, baseline, carrierPower, sidebandEnergy, estimatedDistanceMeters
            )
        )
    }

    private fun triggerHaptic(pattern: LongArray) {
        if (!hapticsEnabled || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
    
    private fun playSubtleTick() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 35)
    }
    
    private fun playUrgentTick() {
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_URGENT, 80)
    }

    fun stop() {
        isRunning = false
        stopHardware()
        tier = "CLEAR"
        delta = 0.0
        baseline = 0.0
    }
    
    private fun stopHardware() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private class FFT(private val n: Int) {
        private val cosTable = DoubleArray(n)
        private val sinTable = DoubleArray(n)

        init {
            for (i in 0 until n) {
                cosTable[i] = cos(2.0 * Math.PI * i / n)
                sinTable[i] = sin(2.0 * Math.PI * i / n)
            }
        }

        fun process(real: DoubleArray, imag: DoubleArray) {
            var j = 0
            for (i in 0 until n - 1) {
                if (i < j) {
                    val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                    val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
                }
                var k = n / 2
                while (k <= j) {
                    j -= k
                    k /= 2
                }
                j += k
            }
            
            var step = 1
            while (step < n) {
                val jump = step * 2
                val angleStep = n / jump
                for (i in 0 until step) {
                    val c = cosTable[i * angleStep]
                    val s = sinTable[i * angleStep]
                    for (k in i until n step jump) {
                        val realT = c * real[k + step] + s * imag[k + step]
                        val imagT = c * imag[k + step] - s * real[k + step]
                        real[k + step] = real[k] - realT
                        imag[k + step] = imag[k] - imagT
                        real[k] += realT
                        imag[k] += imagT
                    }
                }
                step *= 2
            }
        }
    }
}

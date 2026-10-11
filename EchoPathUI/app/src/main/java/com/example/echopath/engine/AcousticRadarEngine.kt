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
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class RadarTelemetry(
    val isRunning: Boolean,
    val isSimulated: Boolean,
    val carrierFreq: Int,
    val tier: String,
    val delta: Double,
    val baseline: Double,
    val carrierPower: Double,
    val sidebandEnergy: Double,
    val estimatedDistanceMeters: Double,
    val obstacleType: String = "Unknown"
)

interface RadarUpdateListener {
    fun onRadarUpdate(telemetry: RadarTelemetry)
}

class AcousticRadarEngine(private val context: Context) {
    
    private var listener: RadarUpdateListener? = null
    
    @Volatile var isRunning = false
    @Volatile var isSimulated = false
    
    var carrierFreq = 18500
    var sensitivityThreshold = 3.5
    var soundEnabled = true
    var hapticsEnabled = true
    
    private var tier = "CLEAR"
    private var delta = 0.0
    private var baseline = 0.0
    private var lowFreqBaseline = 0.0
    private var carrierPower = 0.0
    private var sidebandEnergy = 0.0
    private var estimatedDistanceMeters = 2.0
    
    private var frameCount = 0
    private var consecutiveHits = 0
    private var candidateLag = 0
    private var smoothDistance = 0.0
    private var lastHapticTime = 0L

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
    
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    
    private var lastTier1Time = 0L
    private var lastTier2Time = 0L
    
    private var tflite: Interpreter? = null

    init {
        try {
            tflite = Interpreter(loadModelFile("radar_model.tflite"))
            Log.d("AcousticRadar", "Successfully loaded Edge AI model")
        } catch (e: Exception) {
            Log.w("AcousticRadar", "Edge AI model not found, using heuristic fallback.", e)
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun setUpdateListener(listener: RadarUpdateListener) {
        this.listener = listener
    }

    @SuppressLint("MissingPermission")
    fun start(freq: Int = 19500, sensitivity: Double = 3.0, soundEnabled: Boolean = false, forceSimulation: Boolean = false) {
        if (isRunning) return
        
        this.carrierFreq = freq
        this.sensitivityThreshold = sensitivity
        this.soundEnabled = soundEnabled
        this.isSimulated = forceSimulation
        this.isRunning = true
        this.baseline = 0.0
        this.delta = 0.0
        this.frameCount = 0
        this.consecutiveHits = 0
        this.candidateLag = 0
        this.smoothDistance = 0.0
        this.lastHapticTime = 0L
        this.tier = "CLEAR"
        this.estimatedDistanceMeters = 2.0
        
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
                .setBufferSizeInBytes(max(minBufferSizeTrack, fftSize * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // 48-sample (~1.0ms) silent linear ultrasound chirp (19.5kHz to 21.5kHz)
            // Followed by silence across the rest of the 2048-sample block (42.66ms repetition period)
            // Hann windowing eliminates speaker popping / clicking artifacts completely
            val toneBuffer = ShortArray(fftSize)
            val chirpLength = 48
            val f0 = 19500.0
            val f1 = 21500.0
            val duration = chirpLength.toDouble() / sampleRate

            for (i in 0 until chirpLength) {
                val t = i.toDouble() / sampleRate
                val phase = 2.0 * Math.PI * (f0 * t + 0.5 * (f1 - f0) / duration * t * t)
                val w = 0.5 * (1.0 - cos(2.0 * Math.PI * i / (chirpLength - 1)))
                val sample = sin(phase) * w
                toneBuffer[i] = (sample * 32767.0 * 0.65).toInt().toShort()
            }
            for (i in chirpLength until fftSize) {
                toneBuffer[i] = 0
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
            
            var audioRecordLocal = AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBufferSizeRecord, fftSize * 4)
            )
            
            if (audioRecordLocal.state != AudioRecord.STATE_INITIALIZED) {
                audioRecordLocal.release()
                audioRecordLocal = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBufferSizeRecord, fftSize * 4)
                )
            }

            if (audioRecordLocal.state != AudioRecord.STATE_INITIALIZED) {
                audioRecordLocal.release()
                audioRecordLocal = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBufferSizeRecord, fftSize * 4)
                )
            }
            
            if (audioRecordLocal.state != AudioRecord.STATE_INITIALIZED) {
                audioRecordLocal.release()
                throw Exception("Failed to initialize AudioRecord with any AudioSource on this hardware.")
            }
            
            audioRecord = audioRecordLocal
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
            val fft = FFT(fftSize)
            
            // 1. Build and FFT the reference chirp template (identical to transmitter)
            val chirpLength = 48
            val f0 = 19500.0
            val f1 = 21500.0
            val duration = chirpLength.toDouble() / sampleRate

            val templateReal = DoubleArray(fftSize)
            val templateImag = DoubleArray(fftSize)
            for (i in 0 until chirpLength) {
                val t = i.toDouble() / sampleRate
                val phase = 2.0 * Math.PI * (f0 * t + 0.5 * (f1 - f0) / duration * t * t)
                val w = 0.5 * (1.0 - cos(2.0 * Math.PI * i / (chirpLength - 1)))
                templateReal[i] = sin(phase) * w
                templateImag[i] = 0.0
            }
            fft.process(templateReal, templateImag)

            // 2. Precompute analytical auto-correlation profile
            val autoCorrReal = DoubleArray(fftSize)
            val autoCorrImag = DoubleArray(fftSize)
            for (k in 0 until fftSize) {
                val r = templateReal[k]
                val im = templateImag[k]
                autoCorrReal[k] = r * r + im * im
                autoCorrImag[k] = 0.0
            }
            for (k in 0 until fftSize) autoCorrImag[k] = -autoCorrImag[k]
            fft.process(autoCorrReal, autoCorrImag)
            
            val autoCorrNorm = DoubleArray(fftSize)
            val maxAuto = sqrt(autoCorrReal[0] * autoCorrReal[0] + autoCorrImag[0] * autoCorrImag[0])
            for (k in 0 until fftSize) {
                val mag = sqrt(autoCorrReal[k] * autoCorrReal[k] + autoCorrImag[k] * autoCorrImag[k])
                autoCorrNorm[k] = if (maxAuto > 0.0) mag / maxAuto else 0.0
            }

            // Empirical direct crosstalk template (initialized to analytical autoCorrNorm)
            val empiricalTemplate = DoubleArray(fftSize) { autoCorrNorm[it] }

            val audioData = ShortArray(fftSize)
            val real = DoubleArray(fftSize)
            val imag = DoubleArray(fftSize)
            val corrReal = DoubleArray(fftSize)
            val corrImag = DoubleArray(fftSize)
            val envelope = DoubleArray(fftSize)
            val cleanEnvelope = DoubleArray(fftSize)

            while (isRunning && !isSimulated) {
                val readSize = audioRecord?.read(audioData, 0, fftSize) ?: 0
                if (readSize == fftSize) {
                    val now = System.currentTimeMillis()
                    // If haptic motor fired within 65ms, blank echo analysis to prevent motor vibration feedback loop
                    val hapticCooling = (now - lastHapticTime) < 65L

                    for (i in 0 until fftSize) {
                        real[i] = audioData[i].toDouble()
                        imag[i] = 0.0
                    }

                    // Forward FFT of received audio block
                    fft.process(real, imag)

                    // Matched Filter: Multiply with conjugated chirp template
                    for (k in 0 until fftSize) {
                        val yr = real[k]
                        val yi = imag[k]
                        val sr = templateReal[k]
                        val si = templateImag[k]
                        corrReal[k] = yr * sr + yi * si
                        corrImag[k] = yi * sr - yr * si
                    }

                    // IFFT to obtain correlation time series
                    for (k in 0 until fftSize) corrImag[k] = -corrImag[k]
                    fft.process(corrReal, corrImag)

                    var maxDirectAmp = 0.0
                    var directIdx = 0
                    for (k in 0 until fftSize) {
                        val amp = sqrt(corrReal[k] * corrReal[k] + corrImag[k] * corrImag[k]) / fftSize
                        envelope[k] = amp
                        if (amp > maxDirectAmp) {
                            maxDirectAmp = amp
                            directIdx = k
                        }
                    }

                    val safeDirect = max(1.0, maxDirectAmp)

                    // First ~25 frames (~1 sec): Calibrate to phone's physical speaker-to-mic direct crosstalk
                    frameCount++
                    if (frameCount <= 25) {
                        for (k in 0 until fftSize) {
                            val normAmp = envelope[k] / safeDirect
                            val alignedIdx = (k - directIdx + fftSize) % fftSize
                            empiricalTemplate[alignedIdx] = empiricalTemplate[alignedIdx] * 0.7 + normAmp * 0.3
                        }
                        continue
                    }

                    // Subtract direct hardware leakage
                    for (k in 0 until fftSize) {
                        val normAmp = envelope[k] / safeDirect
                        val alignedIdx = (k - directIdx + fftSize) % fftSize
                        val leakage = empiricalTemplate[alignedIdx]
                        cleanEnvelope[k] = max(0.0, normAmp - leakage)
                    }

                    if (hapticCooling) {
                        // Suppress self-vibration feedback transient
                        continue
                    }

                    // Obstacle search window: lag 85 (~30cm) to lag 440 (~1.57m)
                    // Allows detecting obstacles from 30cm all the way to 1.57 meters!
                    val lagStart = 85
                    val lagEnd = 440
                    var searchSum = 0.0
                    val searchCount = lagEnd - lagStart + 1
                    for (lag in lagStart..lagEnd) {
                        val idx = (directIdx + lag) % fftSize
                        searchSum += cleanEnvelope[idx]
                    }
                    val searchMean = searchSum / searchCount

                    // Find prominent obstacle reflection peaks
                    var bestEchoAmp = 0.0
                    var bestEchoLag = 0
                    var bestProminence = 0.0

                    var trackedEchoAmp = 0.0
                    var trackedEchoLag = 0
                    var trackedProminence = 0.0

                    for (lag in lagStart..lagEnd) {
                        val idx = (directIdx + lag) % fftSize
                        val amp = cleanEnvelope[idx]

                        // Must be a local peak
                        val prevIdx = (idx - 1 + fftSize) % fftSize
                        val nextIdx = (idx + 1) % fftSize
                        if (amp <= cleanEnvelope[prevIdx] || amp <= cleanEnvelope[nextIdx]) {
                            continue
                        }

                        // Local background (surrounding ±12 lags, excluding ±2 lags around apex)
                        var localSum = 0.0
                        var localCount = 0
                        for (offset in -12..12) {
                            if (offset in -2..2) continue
                            val neighborLag = lag + offset
                            if (neighborLag in lagStart..lagEnd) {
                                val nIdx = (directIdx + neighborLag) % fftSize
                                localSum += cleanEnvelope[nIdx]
                                localCount++
                            }
                        }
                        val localBg = if (localCount > 0) localSum / localCount else searchMean
                        val prominence = amp - localBg

                        // Range-Adaptive Thresholds (Time-Varying Gain):
                        // Close (<46cm): higher threshold to ignore near-field ringdown
                        // Mid (46cm..85cm): moderate threshold
                        // Far (85cm..1.57m): lower threshold to catch distant object echoes
                        val (minAmp, minProm) = when {
                            lag <= 130 -> Pair(0.12, 0.06)
                            lag <= 240 -> Pair(0.07, 0.04)
                            else -> Pair(0.045, 0.025)
                        }

                        if (amp >= minAmp && amp >= (searchMean * 2.5) && prominence >= minProm) {
                            // If an obstacle was already being tracked, maintain tracking continuity
                            if (candidateLag > 0 && kotlin.math.abs(lag - candidateLag) <= 20) {
                                if (amp > trackedEchoAmp) {
                                    trackedEchoAmp = amp
                                    trackedEchoLag = lag
                                    trackedProminence = prominence
                                }
                            }
                            if (amp > bestEchoAmp) {
                                bestEchoAmp = amp
                                bestEchoLag = lag
                                bestProminence = prominence
                            }
                        }
                    }

                    // Prefer the tracked candidate to prevent target hopping, otherwise use the best candidate
                    val chosenLag = if (trackedEchoLag > 0) trackedEchoLag else bestEchoLag
                    val chosenAmp = if (trackedEchoLag > 0) trackedEchoAmp else bestEchoAmp
                    val chosenProm = if (trackedEchoLag > 0) trackedProminence else bestProminence

                    carrierPower = maxDirectAmp
                    baseline = searchMean

                    if (chosenLag > 0 && maxDirectAmp > 15.0) {
                        // Echo candidate detected: update persistence
                        if (consecutiveHits > 0 && kotlin.math.abs(chosenLag - candidateLag) <= 22) {
                            consecutiveHits = min(6, consecutiveHits + 1)
                        } else {
                            consecutiveHits = 1
                        }
                        candidateLag = chosenLag
                        val rawDistance = (candidateLag.toDouble() * 343.0) / (2.0 * sampleRate)
                        smoothDistance = if (smoothDistance > 0.1) smoothDistance * 0.65 + rawDistance * 0.35 else rawDistance

                        if (consecutiveHits >= 2) {
                            // CONFIRMED REAL OBSTACLE! (2 consecutive frames ~85ms)
                            estimatedDistanceMeters = smoothDistance.coerceIn(0.30, 1.55)
                            delta = chosenProm * 100.0
                            sidebandEnergy = chosenAmp
                            tier = if (estimatedDistanceMeters < 0.60) "CRITICAL" else "CAUTION"
                        } else {
                            if (tier == "CLEAR") {
                                delta = 0.0
                                estimatedDistanceMeters = 2.0
                            }
                        }
                    } else {
                        // No valid echo: decrease counter (leaky integrator)
                        consecutiveHits = max(0, consecutiveHits - 1)
                        if (consecutiveHits == 0) {
                            candidateLag = 0
                            delta = 0.0
                            estimatedDistanceMeters = 2.0
                            sidebandEnergy = 0.0
                            smoothDistance = 0.0
                            tier = "CLEAR"

                            // Continuous background adaptation in open air:
                            // Slowly track stationary phone body & hand holding leakage
                            val adaptAlpha = 0.03
                            for (k in 0 until fftSize) {
                                val normAmp = envelope[k] / safeDirect
                                val alignedIdx = (k - directIdx + fftSize) % fftSize
                                empiricalTemplate[alignedIdx] = empiricalTemplate[alignedIdx] * (1.0 - adaptAlpha) + normAmp * adaptAlpha
                            }
                        }
                    }

                    evaluateHazards()

                    if (frameCount % 10 == 0) {
                        Log.i("AcousticRadar", "Hits=%d Lag=%d Amp=%.3f Prom=%.3f Mean=%.3f Direct=%.1f Dist=%.2fm Tier=%s".format(
                            consecutiveHits, candidateLag, bestEchoAmp, bestProminence, searchMean, maxDirectAmp, estimatedDistanceMeters, tier
                        ))
                    }
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

    private fun evaluateHazards() {
        if (!isRunning) return
        val now = System.currentTimeMillis()
        
        if (tier != "CLEAR" && delta >= sensitivityThreshold) {
            // Distance-based pulse rate: closer = faster pulsing (110ms .. 480ms)
            val interval = kotlin.math.max(110L, (estimatedDistanceMeters * 400).toLong())
            
            if (now - lastTier1Time > interval) {
                lastTier1Time = now
                // Hard vibration when close, small/gentle vibration when in distance
                triggerDistanceHaptic(estimatedDistanceMeters)
                
                if (soundEnabled) {
                    val tone = if (tier == "CRITICAL") ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP
                    val toneDur = if (tier == "CRITICAL") 40 else 20
                    toneGenerator.startTone(tone, toneDur)
                }
            }
        } else {
            tier = "CLEAR"
            vibrator.cancel()
        }
        
        val type = if (tier != "CLEAR") {
            if (tflite != null) {
                // Physically calibrated features bounded strictly in [0.0, 1.0]
                val safeDirect = if (carrierPower > 1.0) carrierPower else 1.0
                val reflectionRatio = min(1.0f, max(0.0f, (sidebandEnergy / safeDirect).toFloat()))
                val safeBaseline = if (baseline > 0.001) baseline else 0.001
                val snrRatio = min(1.0f, max(0.0f, (sidebandEnergy / (safeBaseline * 20.0)).toFloat()))
                
                val input = arrayOf(floatArrayOf(reflectionRatio, snrRatio))
                val output = arrayOf(floatArrayOf(0f, 0f, 0f))
                try {
                    tflite?.run(input, output)
                    val p = output[0]
                    val detected = when {
                        p[1] >= p[2] -> "Hard Surface"
                        else -> "Soft Object"
                    }
                    if (frameCount % 10 == 0) {
                        Log.i("AcousticRadar", "Edge AI [Clear=%.2f, Hard=%.2f, Soft=%.2f] => %s".format(p[0], p[1], p[2], detected))
                    }
                    detected
                } catch (e: Exception) {
                    "Model Error"
                }
            } else {
                // Fallback heuristic based on physical reflection ratio
                if (sidebandEnergy > carrierPower * 0.12) "Hard Surface" else "Soft Object"
            }
        } else "None"

        listener?.onRadarUpdate(
            RadarTelemetry(
                isRunning, isSimulated, carrierFreq, tier, delta, baseline, carrierPower, sidebandEnergy, estimatedDistanceMeters, type
            )
        )
    }

    private fun triggerDistanceHaptic(distanceMeters: Double) {
        if (!isRunning || !hapticsEnabled || !vibrator.hasVibrator()) return
        
        lastHapticTime = System.currentTimeMillis()
        
        // Proximity from 0.30m (close) to 1.50m (far distance)
        val clampedDist = distanceMeters.coerceIn(0.30, 1.50)
        val proximity = ((1.50 - clampedDist) / (1.50 - 0.30)).coerceIn(0.0, 1.0)
        
        // Amplitude: 30 (small gentle micro-tick when far) to 255 (maximum hard punch when close)
        val amplitude = (30 + (225 * proximity * proximity)).toInt().coerceIn(1, 255)
        // Duration: 18ms (crisp tick when far) to 85ms (deep punch when close)
        val durationMs = (18 + (67 * proximity)).toLong().coerceIn(18L, 85L)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (vibrator.hasAmplitudeControl()) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }
    
    private fun playSubtleTick() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 35)
    }
    
    private fun playUrgentTick() {
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 80)
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
            vibrator.cancel()
            consecutiveHits = 0
            candidateLag = 0
            smoothDistance = 0.0
            
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

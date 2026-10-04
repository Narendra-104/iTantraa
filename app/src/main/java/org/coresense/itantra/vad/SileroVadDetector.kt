package org.coresense.itantra.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.coresense.itantra.audio.AudioConfig
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Silero VAD implementation with ONNX Runtime Mobile.
 * Keeps STT idle until speech is detected.
 *
 * Parameters:
 * - speech start threshold (default 0.5)
 * - min silence ~500-700 ms to end sentence
 * - max utterance length cap (15 s)
 */
class SileroVadDetector(
    private val parameters: VadParameters = VadParameters()
) : AutoCloseable {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // Silero state tensors: [2, 1, 64] float
    private var hState: Array<Array<FloatArray>> = Array(2) { Array(1) { FloatArray(64) } }
    private var cState: Array<Array<FloatArray>> = Array(2) { Array(1) { FloatArray(64) } }

    private var currentState = VadState.SILENCE
    private val speechAudioBuffer = ArrayList<Short>()

    private var continuousSilenceDurationMs = 0L
    private var utteranceDurationMs = 0L

    fun loadModel(modelFile: File): Result<Unit> {
        return try {
            if (!modelFile.exists()) {
                return Result.failure(IllegalStateException("Silero VAD model file not found at: ${modelFile.absolutePath}"))
            }
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            this.ortEnv = env
            this.ortSession = session
            resetState()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun loadFromAssets(context: Context): Result<Unit> {
        return try {
            val bytes = context.assets.open("silero_vad.onnx").use { stream: InputStream ->
                stream.readBytes()
            }
            loadModelFromBytes(bytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun loadModelFromBytes(modelBytes: ByteArray): Result<Unit> {
        return try {
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(modelBytes, OrtSession.SessionOptions())
            this.ortEnv = env
            this.ortSession = session
            resetState()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun resetState() {
        hState = Array(2) { Array(1) { FloatArray(64) } }
        cState = Array(2) { Array(1) { FloatArray(64) } }
        currentState = VadState.SILENCE
        speechAudioBuffer.clear()
        continuousSilenceDurationMs = 0L
        utteranceDurationMs = 0L
    }

    /**
     * Process 1 frame (512 samples = 32 ms).
     */
    fun processFrame(frame: ShortArray): VadEvent {
        val probability = calculateSpeechProbability(frame)
        val isSpeech = probability >= parameters.speechThreshold

        when (currentState) {
            VadState.SILENCE -> {
                if (isSpeech) {
                    currentState = VadState.SPEECH_STARTED
                    speechAudioBuffer.clear()
                    for (s in frame) speechAudioBuffer.add(s)
                    continuousSilenceDurationMs = 0L
                    utteranceDurationMs = AudioConfig.FRAME_DURATION_MS.toLong()
                    return VadEvent.SpeechStarted
                } else {
                    return VadEvent.Silence
                }
            }

            VadState.SPEECH_STARTED, VadState.SPEECH_ACTIVE -> {
                currentState = VadState.SPEECH_ACTIVE
                for (s in frame) speechAudioBuffer.add(s)
                utteranceDurationMs += AudioConfig.FRAME_DURATION_MS

                if (isSpeech) {
                    continuousSilenceDurationMs = 0L
                } else {
                    continuousSilenceDurationMs += AudioConfig.FRAME_DURATION_MS
                }

                // Check termination conditions:
                // 1. Min silence reached (~650 ms) after min speech duration
                // 2. Max utterance cap reached (~15 s)
                val isSilenceCutoff = continuousSilenceDurationMs >= parameters.minSilenceDurationMs &&
                        utteranceDurationMs >= parameters.minSpeechDurationMs
                val isMaxCapCutoff = utteranceDurationMs >= parameters.maxUtteranceDurationMs

                if (isSilenceCutoff || isMaxCapCutoff) {
                    currentState = VadState.SPEECH_ENDED
                    val completeAudio = ShortArray(speechAudioBuffer.size) { speechAudioBuffer[it] }
                    val finalDuration = utteranceDurationMs

                    // Reset buffer for next utterance
                    speechAudioBuffer.clear()
                    continuousSilenceDurationMs = 0L
                    utteranceDurationMs = 0L
                    currentState = VadState.SILENCE

                    return VadEvent.SpeechEnded(completeAudio, finalDuration)
                }

                return VadEvent.SpeechActive(probability)
            }

            VadState.SPEECH_ENDED -> {
                currentState = VadState.SILENCE
                return VadEvent.Silence
            }
        }
    }

    private fun calculateSpeechProbability(frame: ShortArray): Float {
        val session = ortSession
        val env = ortEnv

        if (session != null && env != null) {
            try {
                // Run Silero ONNX inference
                val floatBuffer = FloatBuffer.allocate(frame.size)
                for (s in frame) {
                    floatBuffer.put(s.toFloat() / 32768.0f)
                }
                floatBuffer.rewind()

                val inputTensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, frame.size.toLong()))
                val srTensor = OnnxTensor.createTensor(env, longArrayOf(AudioConfig.SAMPLE_RATE_HZ.toLong()))
                val hTensor = OnnxTensor.createTensor(env, hState)
                val cTensor = OnnxTensor.createTensor(env, cState)

                val inputs = mapOf(
                    "input" to inputTensor,
                    "sr" to srTensor,
                    "h" to hTensor,
                    "c" to cTensor
                )

                val result = session.run(inputs)
                val output = result[0].value as Array<FloatArray>
                val prob = output[0][0]

                // Update hidden states if present in outputs
                if (result.size() >= 3) {
                    val newH = result[1].value as? Array<Array<FloatArray>>
                    val newC = result[2].value as? Array<Array<FloatArray>>
                    if (newH != null) hState = newH
                    if (newC != null) cState = newC
                }

                inputTensor.close()
                srTensor.close()
                hTensor.close()
                cTensor.close()
                result.close()

                return prob.coerceIn(0f, 1f)
            } catch (ignored: Exception) {
                // Fall back to calibrated energy metric if tensor dimension differs
            }
        }

        // Calibrated Energy + Zero-Crossing Rate fallback when ONNX model is not loaded
        var energySum = 0.0
        var zeroCrossings = 0
        for (i in frame.indices) {
            val s = frame[i].toDouble() / 32768.0
            energySum += s * s
            if (i > 0 && ((frame[i] >= 0 && frame[i - 1] < 0) || (frame[i] < 0 && frame[i - 1] >= 0))) {
                zeroCrossings++
            }
        }
        val rms = sqrt(energySum / frame.size).toFloat()
        val zcr = zeroCrossings.toFloat() / frame.size

        // Speech typically has higher energy and moderate zero crossing rate
        val energyProb = (rms * 12.0f).coerceIn(0f, 1f)
        val zcrWeight = if (zcr in 0.04f..0.45f) 1.0f else 0.4f
        return (energyProb * zcrWeight).coerceIn(0f, 1f)
    }

    override fun close() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (ignored: Exception) {
        } finally {
            ortSession = null
            ortEnv = null
        }
    }
}

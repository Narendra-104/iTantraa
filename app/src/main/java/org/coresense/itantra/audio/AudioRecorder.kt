package org.coresense.itantra.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Single mic owner for iTantra.
 * Captures 16 kHz mono 16-bit PCM in 32 ms frames (512 samples).
 * Calculates real-time measured RMS amplitude for visualizer.
 */
class AudioRecorder {

    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val _audioFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<ShortArray> = _audioFrames.asSharedFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(scope: CoroutineScope): Result<Unit> {
        if (isRecording.get()) return Result.success(Unit)

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE_HZ,
            AudioConfig.CHANNEL_CONFIG,
            AudioConfig.AUDIO_FORMAT
        )

        val bufferSize = maxOf(minBufferSize, AudioConfig.BYTES_PER_FRAME * 4)

        return try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioConfig.SAMPLE_RATE_HZ,
                AudioConfig.CHANNEL_CONFIG,
                AudioConfig.AUDIO_FORMAT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return Result.failure(IllegalStateException("AudioRecord initialization failed (state != INITIALIZED)"))
            }

            recorder.startRecording()
            audioRecord = recorder
            isRecording.set(true)
            _isCapturing.value = true

            recordingJob = scope.launch(Dispatchers.IO) {
                val frameBuffer = ShortArray(AudioConfig.SAMPLES_PER_FRAME)
                while (isActive && isRecording.get()) {
                    var readTotal = 0
                    while (readTotal < AudioConfig.SAMPLES_PER_FRAME && isRecording.get() && isActive) {
                        val read = recorder.read(
                            frameBuffer,
                            readTotal,
                            AudioConfig.SAMPLES_PER_FRAME - readTotal
                        )
                        if (read > 0) {
                            readTotal += read
                        } else if (read < 0) {
                            break
                        }
                    }

                    if (readTotal == AudioConfig.SAMPLES_PER_FRAME) {
                        // Calculate real measured RMS amplitude
                        var sumSquare = 0.0
                        for (sample in frameBuffer) {
                            val normalized = sample.toDouble() / 32768.0
                            sumSquare += normalized * normalized
                        }
                        val rms = sqrt(sumSquare / AudioConfig.SAMPLES_PER_FRAME).toFloat()
                        _audioLevel.value = (rms * 3.5f).coerceIn(0f, 1f)

                        _audioFrames.emit(frameBuffer.clone())
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            isRecording.set(false)
            _isCapturing.value = false
            Result.failure(e)
        }
    }

    @Synchronized
    fun stop() {
        if (!isRecording.get()) return
        isRecording.set(false)
        _isCapturing.value = false
        _audioLevel.value = 0f

        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        } catch (ignored: Exception) {
        } finally {
            audioRecord = null
        }
    }
}

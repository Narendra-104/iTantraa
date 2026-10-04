package org.coresense.itantra.audio

import android.media.AudioFormat

object AudioConfig {
    const val SAMPLE_RATE_HZ = 16000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    
    // 32 ms at 16 kHz = 512 samples
    const val FRAME_DURATION_MS = 32
    const val SAMPLES_PER_FRAME = 512
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE // 1024 bytes
}

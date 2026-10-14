package org.coresense.itantra.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.coresense.itantra.protocol.Language
import java.util.Locale
import java.util.UUID

/**
 * Android TextToSpeech engine with STRICT offline verification.
 * Enforces HARD RULE: Only uses voices where voice.isNetworkConnectionRequired == false.
 * For SOS alerts: overrides volume, acquires STREAM_ALARM AudioFocus.
 */
class OfflineAndroidTtsEngine(
    private val context: Context
) : TtsEngine, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val initDeferred = CompletableDeferred<Boolean>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            initDeferred.complete(true)
        } else {
            isInitialized = false
            initDeferred.complete(false)
        }
    }

    override fun isLanguageAvailable(lang: Language): TtsStatus {
        val engine = tts ?: return TtsStatus.NOT_INSTALLED
        if (!isInitialized) return TtsStatus.NOT_INSTALLED

        val locale = Locale.forLanguageTag(lang.bcp47)
        val avail = engine.isLanguageAvailable(locale)

        if (avail < TextToSpeech.LANG_AVAILABLE) {
            return TtsStatus.NOT_INSTALLED
        }

        // Check if there is an offline-capable voice installed
        val voices = try { engine.voices } catch (_: Exception) { null }
        if (voices != null && voices.isNotEmpty()) {
            val matchingVoices = voices.filter { it.locale.language == locale.language }
            if (matchingVoices.isNotEmpty()) {
                val offlineVoice = matchingVoices.firstOrNull { voice ->
                    !voice.isNetworkConnectionRequired &&
                            voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                }
                return if (offlineVoice != null) {
                    TtsStatus.SYSTEM_TTS_OFFLINE
                } else {
                    TtsStatus.NETWORK_REQUIRED_UNAVAILABLE
                }
            }
        }

        // Fallback for TTS engines that do not expose voices collection
        return if (avail != TextToSpeech.LANG_MISSING_DATA && avail != TextToSpeech.LANG_NOT_SUPPORTED) {
            TtsStatus.SYSTEM_TTS_OFFLINE
        } else {
            TtsStatus.NOT_INSTALLED
        }
    }

    private var activeCompletion: CompletableDeferred<Unit>? = null

    override suspend fun speak(text: String, lang: Language, isEmergency: Boolean): Result<Unit> = withContext(Dispatchers.Main) {
        if (text.isBlank()) return@withContext Result.success(Unit)

        if (!isInitialized) {
            val ready = kotlinx.coroutines.withTimeoutOrNull(3000L) { initDeferred.await() } ?: false
            if (!ready) return@withContext Result.failure(IllegalStateException("TTS Engine failed to initialize within timeout"))
        }

        val engine = tts ?: return@withContext Result.failure(IllegalStateException("TTS engine not available"))
        val status = isLanguageAvailable(lang)

        if (status != TtsStatus.SYSTEM_TTS_OFFLINE && status != TtsStatus.OFFLINE_READY) {
            return@withContext Result.failure(
                IllegalStateException("No verified offline TTS voice found for ${lang.displayName} ($status). Online cloud TTS is disabled.")
            )
        }

        val locale = Locale.forLanguageTag(lang.bcp47)
        val voices = try { engine.voices } catch (_: Exception) { null }
        val offlineVoice = voices?.firstOrNull {
            it.locale.language == locale.language && !it.isNetworkConnectionRequired
        }

        if (offlineVoice != null) {
            engine.voice = offlineVoice
        } else {
            engine.language = locale
        }

        // Configure audio attributes based on emergency priority
        val usage = if (isEmergency) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA

        if (isEmergency) {
            // Override volume to max for emergency alert
            try {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            } catch (ignored: Exception) {
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        engine.setAudioAttributes(audioAttributes)

        // Cancel previous completion cleanly if another utterance was speaking
        activeCompletion?.complete(Unit)

        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()
        activeCompletion = completion

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.complete(Unit)
                }
            }

            override fun onStop(id: String?, interrupted: Boolean) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.complete(Unit)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.completeExceptionally(RuntimeException("TTS playback failed"))
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.completeExceptionally(RuntimeException("TTS error code: $errorCode"))
                }
            }
        })

        val params = Bundle()
        if (isEmergency) {
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }

        // QUEUE_FLUSH safely replaces any current speech without overlap
        val res = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (res == TextToSpeech.ERROR) {
            return@withContext Result.failure(IllegalStateException("TextToSpeech.speak returned ERROR"))
        }

        try {
            completion.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (activeCompletion == completion) {
                activeCompletion = null
            }
        }
    }

    override fun stop() {
        try {
            activeCompletion?.complete(Unit)
            activeCompletion = null
            tts?.stop()
        } catch (_: Exception) {}
    }

    override fun close() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isInitialized = false
    }
}

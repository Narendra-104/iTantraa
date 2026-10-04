package org.coresense.itantra.emergency

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.coresense.itantra.MainActivity
import org.coresense.itantra.R
import org.coresense.itantra.protocol.Packet
import org.coresense.itantra.tts.TtsEngine

class SosAlertManager(
    private val context: Context,
    private val ttsEngine: TtsEngine,
    private val scope: CoroutineScope
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private var activeRingtone: android.media.Ringtone? = null

    init {
        createEmergencyNotificationChannel()
    }

    fun stopAlert() {
        try {
            activeRingtone?.stop()
            activeRingtone = null
            vibrator?.cancel()
        } catch (ignored: Exception) {
        }
    }

    private fun createEmergencyNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "iTantra Emergency SOS Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority full-screen emergency alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 800)
                setSound(alarmSound, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun triggerIncomingSosAlert(sosPacket: Packet, receiverLat: Double?, receiverLon: Double?) {
        // 1. Override media / alarm volume to MAX for the alert
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            activeRingtone = RingtoneManager.getRingtone(context, alarmSound)
            activeRingtone?.play()
        } catch (ignored: Exception) {
        }

        // 2. Audio Focus request with USAGE_ALARM to not be interrupted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }

        // 3. Vibration pattern
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 600, 200, 600, 200, 1000),
                    -1
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 600, 200, 600, 200, 1000), -1)
        }

        // 4. Calculate relative distance/bearing if coordinates are available
        val distanceText = if (sosPacket.hasLocation && receiverLat != null && receiverLon != null) {
            val dist = Haversine.distanceMeters(
                receiverLat,
                receiverLon,
                sosPacket.latitudeDegrees!!,
                sosPacket.longitudeDegrees!!
            )
            val bearing = Haversine.bearingDegrees(
                receiverLat,
                receiverLon,
                sosPacket.latitudeDegrees!!,
                sosPacket.longitudeDegrees!!
            )
            val cardinal = Haversine.bearingToCardinal(bearing)
            val distStr = if (dist < 1000) "${dist.toInt()}m" else String.format("%.1fkm", dist / 1000.0)
            " ($distStr $cardinal)"
        } else ""

        // 5. Full-screen intent Notification
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_SOS_ALERT", true)
            putExtra("EXTRA_SENDER_ID", sosPacket.senderId)
            putExtra("EXTRA_TEXT", sosPacket.text)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1002,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🚨 EMERGENCY SOS FROM ${sosPacket.senderId}$distanceText")
            .setContentText(sosPacket.text.ifBlank { "Immediate Assistance Requested!" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        // 6. Speak in sender's language offline
        scope.launch(Dispatchers.Main) {
            val alertSpeech = "Emergency alert from ${sosPacket.senderId}. ${sosPacket.text}"
            ttsEngine.speak(alertSpeech, sosPacket.lang, isEmergency = true)
        }
    }

    companion object {
        const val CHANNEL_ID = "channel_emergency_sos"
        const val NOTIFICATION_ID = 9999
    }
}

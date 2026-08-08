package io.karsmiths.ntfy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class NtfyForegroundService : Service() {

    private var isServiceStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionThread: Thread? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_TOPIC = "EXTRA_TOPIC"
        const val EXTRA_AUTH = "EXTRA_AUTH"

        private const val WAKE_LOCK_TAG = "NtfyPlugin:lock"
        private const val NOTIFICATION_CHANNEL_ID = "ntfy_plugin_channel"
        private const val NOTIFICATION_MSG_CHANNEL_ID = "ntfy_plugin_msg_channel"
        private const val NOTIFICATION_SERVICE_ID = 1001
        
        var messageListener: ((String) -> Unit)? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_START -> {
                    val url = intent.getStringExtra(EXTRA_URL) ?: "https://notify.behamad.ir"
                    val topic = intent.getStringExtra(EXTRA_TOPIC) ?: ""
                    val auth = intent.getStringExtra(EXTRA_AUTH)
                    startService(url, topic, auth)
                }
                ACTION_STOP -> stopService()
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun startService(baseUrl: String, topic: String, auth: String?) {
        if (isServiceStarted) return
        isServiceStarted = true

        val notification = createServiceNotification("Listening for notifications on $topic")
        startForeground(NOTIFICATION_SERVICE_ID, notification)

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                acquire()
            }
        }

        connectionThread = thread {
            connectAndListen(baseUrl, topic, auth)
        }
    }

    private fun stopService() {
        isServiceStarted = false
        connectionThread?.interrupt()
        connectionThread = null

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        stopForeground(true)
        stopSelf()
    }

    private fun connectAndListen(baseUrl: String, topic: String, auth: String?) {
        val topicUrl = "$baseUrl/$topic/json"
        while (isServiceStarted) {
            try {
                val url = URL(topicUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 0 // Infinite read timeout for SSE
                if (auth != null) {
                    connection.setRequestProperty("Authorization", auth)
                }

                val inputStream = connection.inputStream
                val reader = BufferedReader(InputStreamReader(inputStream))
                
                while (isServiceStarted) {
                    val line = reader.readLine() ?: break
                    if (line.isNotEmpty()) {
                        handleMessage(line)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Sleep before reconnecting
                try {
                    Thread.sleep(5000)
                } catch (ie: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun handleMessage(jsonString: String) {
        try {
            val gson = Gson()
            val jsonObject = gson.fromJson(jsonString, JsonObject::class.java)
            
            val event = jsonObject.get("event")?.asString
            if (event == "message") {
                val title = jsonObject.get("title")?.asString ?: "New Notification"
                val message = jsonObject.get("message")?.asString ?: ""
                val id = jsonObject.get("id")?.asString ?: System.currentTimeMillis().toString()
                
                val iconUrl = jsonObject.get("icon")?.asString
                var attachmentUrl: String? = null
                if (jsonObject.has("attachment") && jsonObject.get("attachment").isJsonObject) {
                    attachmentUrl = jsonObject.getAsJsonObject("attachment").get("url")?.asString
                }
                
                showNotification(title, message, id, iconUrl, attachmentUrl)
                
                // Also pass back to Flutter if listening
                messageListener?.invoke(jsonString)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showNotification(title: String, message: String, id: String, iconUrl: String?, attachmentUrl: String?) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Find launch intent for the flutter app
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            null
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_MSG_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Fallback icon
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val iconBitmap = iconUrl?.let { getBitmapFromUrl(it) }
        if (iconBitmap != null) {
            builder.setLargeIcon(iconBitmap)
        }

        if (attachmentUrl != null) {
            val attachmentBitmap = getBitmapFromUrl(attachmentUrl)
            if (attachmentBitmap != null) {
                builder.setStyle(NotificationCompat.BigPictureStyle()
                    .bigPicture(attachmentBitmap)
                    .bigLargeIcon(iconBitmap))
            } else {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(message))
            }
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(message))
        }

        notificationManager.notify(id.hashCode(), builder.build())
    }

    private fun getBitmapFromUrl(urlStr: String): Bitmap? {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.connect()
            BitmapFactory.decodeStream(connection.inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Ntfy Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            manager.createNotificationChannel(serviceChannel)

            val msgChannel = NotificationChannel(
                NOTIFICATION_MSG_CHANNEL_ID,
                "Ntfy Messages",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(msgChannel)
        }
    }

    private fun createServiceNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            null
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("سرویس اعلان بهامد")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

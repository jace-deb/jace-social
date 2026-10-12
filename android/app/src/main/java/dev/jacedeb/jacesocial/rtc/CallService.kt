package dev.jacedeb.jacesocial.rtc

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.jacedeb.jacesocial.MainActivity
import dev.jacedeb.jacesocial.R
import dev.jacedeb.jacesocial.data.Notify

/** Keeps a call or voice room going while you use other apps (and is what Android requires for
 *  sharing the screen). Shows an "in a call" notification that opens the app. */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "In a call"
        val screen = intent?.getBooleanExtra("screen", false) == true
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, Notify.ONGOING)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title)
            .setContentText(if (screen) "Sharing your screen" else "Tap to go back to the call")
            .setOngoing(true).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_CALL).build()
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (screen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        runCatching { ServiceCompat.startForeground(this, 7, n, types) }
            .onFailure { android.util.Log.w("CallService", "foreground", it) }
        return START_NOT_STICKY
    }

    companion object {
        /** Start (or update) the service: screen = also sharing the screen. */
        fun start(context: Context, title: String, screen: Boolean = false) {
            val i = Intent(context, CallService::class.java).putExtra("title", title).putExtra("screen", screen)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, i) }
        }
        fun stop(context: Context) { context.stopService(Intent(context, CallService::class.java)) }
    }
}

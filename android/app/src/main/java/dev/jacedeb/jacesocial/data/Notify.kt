package dev.jacedeb.jacesocial.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.jacedeb.jacesocial.MainActivity
import dev.jacedeb.jacesocial.R

/** Notifications while the app runs in the background (messages, friend requests, calls), following
 *  your settings like the website: Do Not Disturb, "only mentions", muted chats, sounds. */
object Notify {
    const val MESSAGES = "messages"
    const val CALLS = "calls"
    const val ONGOING = "ongoing"
    private lateinit var app: Context
    private var next = 100

    fun init(context: Context) {
        app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), null)
            })
            nm.createNotificationChannel(NotificationChannel(ONGOING, "Calls in progress", NotificationManager.IMPORTANCE_LOW))
        }
    }

    val inForeground: Boolean get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** important = direct messages, mentions, friend requests and calls (shown even with "only mentions"). */
    fun show(me: Me?, title: String, text: String, important: Boolean, muteKeys: List<String> = emptyList(), call: Boolean = false, open: String? = null) {
        if (inForeground && !call) return
        val s = me?.settings ?: Settings()
        if (me?.status == "dnd" && !call) return
        if (s.notify == "none" && !call) return
        if (s.notify == "mentions" && !important) return
        if (!important && muteKeys.any { (s.muted ?: emptyList()).contains(it) }) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { open?.let { putExtra("open", it) } }
        val pi = PendingIntent.getActivity(app, next, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, if (call) CALLS else MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (call) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .apply { if (s.sounds == false && !call) setSilent(true) }
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(if (call) 1 else next++, n) }
    }

    fun cancelCall() = runCatching { NotificationManagerCompat.from(app).cancel(1) }
}

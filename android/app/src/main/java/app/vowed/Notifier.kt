package app.vowed

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.vowed.data.NoteItem

/**
 * Local notifications for squad activity. They are shown from the app's own check of the server (while the app is open or was
 * used recently); true background push needs a Firebase project, which is a separate setup step (docs/runbook.md).
 */
object Notifier {
    private const val CHANNEL = "squad"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Squad activity", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Nudges and check-ins from your squads" })
        }
    }

    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun short(w: String) = if (w.length > 10) w.take(4) + "…" + w.takeLast(4) else w

    /** Only items worth interrupting someone for: a nudge aimed at them, a squad mate's check-in, a finished challenge. */
    fun show(ctx: Context, n: NoteItem) {
        if (!allowed(ctx)) return
        val (title, text) = when (n.kind) {
            "nudge" -> "${n.squadName}: a nudge for you" to "${short(n.wallet)} is asking you to check in today."
            "checked_in" -> n.squadName to "${short(n.wallet)} checked in."
            "settled" -> n.squadName to "${short(n.wallet)} finished a challenge."
            else -> return
        }
        ensureChannel(ctx)
        val open = PendingIntentOpen.main(ctx)
        val notif = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(open).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(n.id.toInt(), notif) }
    }
}

internal object PendingIntentOpen {
    fun main(ctx: Context): android.app.PendingIntent =
        android.app.PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
}

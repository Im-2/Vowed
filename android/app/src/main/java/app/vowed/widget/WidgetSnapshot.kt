package app.vowed.widget

import android.content.Context

/**
 * What the home-screen widget shows. The app writes it whenever the Today list refreshes; the widget only reads this small summary
 * (no network, no wallet, no keys), so it can update itself quickly and never exposes anything beyond counts and the best streak.
 */
data class WidgetSnapshot(
    val signedIn: Boolean,
    /** open challenges whose check-in window is open and not yet done */
    val dueToday: Int,
    /** open challenges already done today */
    val doneToday: Int,
    val bestStreak: Int,
    val updatedAtMillis: Long,
) {
    /** The headline line of the widget. */
    fun headline(): String = when {
        !signedIn -> "Open Vowed to sign in"
        dueToday > 0 -> "$dueToday check-in${if (dueToday == 1) "" else "s"} to do today"
        doneToday > 0 -> "All done today"
        else -> "Nothing due right now"
    }

    fun streakLine(): String = if (bestStreak > 0) "Streak $bestStreak day${if (bestStreak == 1) "" else "s"}" else "No streak yet"

    companion object {
        private const val FILE = "vowed_widget"
        val EMPTY = WidgetSnapshot(false, 0, 0, 0, 0)

        fun read(ctx: Context): WidgetSnapshot {
            val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return WidgetSnapshot(sp.getBoolean("in", false), sp.getInt("due", 0), sp.getInt("done", 0), sp.getInt("streak", 0), sp.getLong("at", 0))
        }

        fun write(ctx: Context, s: WidgetSnapshot) {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("in", s.signedIn).putInt("due", s.dueToday).putInt("done", s.doneToday).putInt("streak", s.bestStreak).putLong("at", s.updatedAtMillis).apply()
        }
    }
}

/** Asks Android to add the Vowed widget to the home screen (the system shows its own confirmation). Returns false when the launcher cannot pin widgets. */
fun requestPinWidget(ctx: Context): Boolean {
    val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
    if (!mgr.isRequestPinAppWidgetSupported) return false
    return mgr.requestPinAppWidget(android.content.ComponentName(ctx, VowedWidgetReceiver::class.java), null, null)
}

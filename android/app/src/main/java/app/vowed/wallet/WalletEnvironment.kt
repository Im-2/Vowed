package app.vowed.wallet

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.vowed.BuildConfig

/** Facts about this phone and the wallet apps on it, for the diagnostics and for bringing Vowed back in front. No personal data. */
object WalletEnvironment {
    /** Apps that answer the Mobile Wallet Adapter association link, with their version, for example "app.phantom 25.1.0". */
    fun wallets(ctx: Context): String = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("solana-wallet:/v1/associate/local"))
        val found = ctx.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()
        if (found.isEmpty()) "none found (or not visible to the app)" else found.joinToString(", ") { pkg ->
            val v = runCatching { ctx.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: "?"
            "$pkg $v"
        }
    } catch (e: Exception) {
        "could not be read (${e.javaClass.simpleName})"
    }

    fun header(ctx: Context): List<String> = listOf(
        "app ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}), backend ${BuildConfig.BACKEND_URL}",
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
        "wallet apps that handle solana-wallet: links: ${wallets(ctx)}",
    )

    /**
     * Brings Vowed's screen back on top. A wallet started for a result runs INSIDE Vowed's task, and some wallets (Phantom) leave their own screen on
     * top of Vowed's when they are done. Moving the whole task to the front would then show the wallet again, so the first step asks for Vowed's
     * activity itself to be reordered to the top of its task. Android may refuse from the background; the result goes into the trace.
     */
    fun bringToFront(ctx: Context): String {
        val out = ArrayList<String>()
        try {
            ctx.startActivity(
                Intent(ctx, app.vowed.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            out.add("Vowed's screen reordered to the top of its task")
        } catch (e: Exception) {
            out.add("reorder failed (${e.javaClass.simpleName}: ${e.message})")
        }
        try {
            val task = ctx.getSystemService(ActivityManager::class.java).appTasks.firstOrNull()
            if (task == null) out.add("no task to move") else { task.moveToFront(); out.add("task moved to the front") }
        } catch (e: Exception) {
            out.add("moveToFront failed (${e.javaClass.simpleName}: ${e.message})")
        }
        return out.joinToString("; ")
    }

    /** Where the wallet's screen is while it is open: how many screens Vowed's task holds and which one is on top (own task only, no other app's data). */
    fun taskSummary(ctx: Context): String = try {
        val info = ctx.getSystemService(ActivityManager::class.java).appTasks.firstOrNull()?.taskInfo
        if (info == null) "no task"
        else if (Build.VERSION.SDK_INT < 29) "task info needs Android 10"
        else "${info.numActivities} screens, top ${info.topActivity?.flattenToShortString() ?: "?"}, base ${info.baseActivity?.packageName ?: "?"}"
    } catch (e: Exception) {
        "unreadable (${e.javaClass.simpleName})"
    }
}

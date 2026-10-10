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

    /** Asks Android to bring Vowed's task to the front. Android may refuse from the background; the result goes into the trace. */
    fun bringToFront(ctx: Context): String = try {
        val task = ctx.getSystemService(ActivityManager::class.java).appTasks.firstOrNull()
        if (task == null) "no task to move" else { task.moveToFront(); "moveToFront requested" }
    } catch (e: Exception) {
        "failed (${e.javaClass.simpleName}: ${e.message})"
    }
}

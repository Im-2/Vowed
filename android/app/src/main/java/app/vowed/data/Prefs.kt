package app.vowed.data

import android.content.Context
import app.vowed.BuildConfig

/**
 * Small local settings. Nothing secret is stored: the wallet's auth token is an opaque reference the wallet app itself checks,
 * and the backend token lives in memory only (it is re-obtained through the wallet when the app restarts).
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("vowed", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var wallet: String?
        get() = sp.getString("wallet", null)
        set(v) = sp.edit().putString("wallet", v).apply()

    var mwaAuthToken: String?
        get() = sp.getString("mwa_auth_token", null)
        set(v) = sp.edit().putString("mwa_auth_token", v).apply()

    /** Device id (hex sha256 of the proof key) that is registered with the backend for [wallet]. */
    fun registeredDevice(wallet: String): String? = sp.getString("device_$wallet", null)
    fun setRegisteredDevice(wallet: String, id: String?) = sp.edit().apply { if (id == null) remove("device_$wallet") else putString("device_$wallet", id) }.apply()
    fun deviceTrustCap(wallet: String): String? = sp.getString("trust_$wallet", null)
    fun setDeviceTrustCap(wallet: String, cap: String?) = sp.edit().apply { if (cap == null) remove("trust_$wallet") else putString("trust_$wallet", cap) }.apply()

    /** Debug builds can point the app at another backend; release builds ignore this and use the built-in URL. */
    var backendUrl: String
        get() = if (BuildConfig.DEBUG) sp.getString("backend_url", null) ?: BuildConfig.BACKEND_URL else BuildConfig.BACKEND_URL
        set(v) = sp.edit().putString("backend_url", v).apply()

    /** A goal's place (latitude, longitude) stays on the phone; the backend only ever hears "inside" and dwell seconds. */
    fun setPlace(pool: String, lat: Double, lon: Double) = sp.edit().putString("place_$pool", "$lat,$lon").apply()
    fun place(pool: String): Pair<Double, Double>? =
        sp.getString("place_$pool", null)?.split(",")?.takeIf { it.size == 2 }?.let { it[0].toDoubleOrNull()?.let { a -> it[1].toDoubleOrNull()?.let { b -> a to b } } }

    /** Step-counter reading taken when a day's check-in first opened; the day's steps are the difference to it. */
    fun stepBaseline(pool: String, day: Int): Long? = sp.getLong("steps_${pool}_$day", -1L).takeIf { it >= 0 }
    fun setStepBaseline(pool: String, day: Int, total: Long) = sp.edit().putLong("steps_${pool}_$day", total).apply()

    /** Which app a usage goal watches, if the user changed it from the template. */
    fun setWatchedApp(pool: String, name: String) = sp.edit().putString("app_$pool", name).apply()
    fun watchedApp(pool: String): String? = sp.getString("app_$pool", null)

    var askedNotify: Boolean
        get() = sp.getBoolean("asked_notify", false)
        set(v) = sp.edit().putBoolean("asked_notify", v).apply()

    /** The time of the last squad-notification check (unix seconds); 0 means never looked. */
    var notesSince: Long
        get() = sp.getLong("notes_since", 0L)
        set(v) = sp.edit().putLong("notes_since", v).apply()

    fun clearAccount() {
        val w = wallet
        sp.edit().remove("wallet").remove("mwa_auth_token").apply()
        if (w != null) { setRegisteredDevice(w, null); setDeviceTrustCap(w, null) }
    }
}

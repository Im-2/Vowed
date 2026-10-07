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

    fun clearAccount() {
        val w = wallet
        sp.edit().remove("wallet").remove("mwa_auth_token").apply()
        if (w != null) { setRegisteredDevice(w, null); setDeviceTrustCap(w, null) }
    }
}

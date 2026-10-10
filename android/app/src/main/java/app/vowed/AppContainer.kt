package app.vowed

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.vowed.data.AccountRepository
import app.vowed.data.BackendApi
import app.vowed.data.Prefs
import app.vowed.data.SessionKeeper
import app.vowed.data.SessionStore
import app.vowed.letters.LetterCipher
import app.vowed.letters.LetterStore
import app.vowed.proof.ProofEngine
import app.vowed.device.DeviceKey
import app.vowed.wallet.AppForeground
import app.vowed.wallet.LifecycleLog
import app.vowed.wallet.WalletDiagnostics
import app.vowed.wallet.WalletEnvironment
import app.vowed.wallet.WalletManager

/** Hand-wired dependencies (no DI framework yet: the graph is small, and fewer moving parts means fewer build surprises). */
class AppContainer(val app: Application) {
    val prefs = Prefs(app)
    val api = BackendApi(baseUrl = { prefs.backendUrl })
    val wallet = WalletManager(prefs, identityUri = BuildConfig.BACKEND_URL, bringToFront = { WalletEnvironment.bringToFront(app) })
    val deviceKey: (String) -> DeviceKey = { DeviceKey(app, it) }
    val letters = LetterStore(java.io.File(app.filesDir, "letters.bin"), LetterCipher { LetterCipher.keystoreKey() })
    val sessions = SessionKeeper(
        store = SessionStore(prefs.sessionKv, LetterCipher { LetterCipher.keystoreKey("vowed-session-v1") }),
        setToken = { api.token = it },
        refreshCall = { api.refreshSession() },
    )
    val account = AccountRepository(this)
    val proofs = ProofEngine(api, { deviceKey(prefs.wallet ?: error("no wallet")) })
}

class VowedApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        LifecycleLog.markProcessStart(System.currentTimeMillis())
        LifecycleLog.record("Application.onCreate (process started)")
        container = AppContainer(this)
        WalletDiagnostics.header = { WalletEnvironment.header(this) }
        // the Android lifecycle of the app's screens, so a wallet problem can be placed next to "Vowed went to the background" or "was restarted"
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun name(a: Activity) = a.javaClass.simpleName
            override fun onActivityCreated(a: Activity, saved: Bundle?) { LifecycleLog.record("onCreate ${name(a)}${if (saved != null) " (restored from saved state: the app was restarted)" else ""}") }
            override fun onActivityStarted(a: Activity) { LifecycleLog.record("onStart ${name(a)}") }
            override fun onActivityResumed(a: Activity) { AppForeground.set(true); LifecycleLog.record("onResume ${name(a)}") }
            override fun onActivityPaused(a: Activity) { AppForeground.set(false); LifecycleLog.record("onPause ${name(a)}") }
            override fun onActivityStopped(a: Activity) { LifecycleLog.record("onStop ${name(a)}") }
            override fun onActivitySaveInstanceState(a: Activity, out: Bundle) { LifecycleLog.record("onSaveInstanceState ${name(a)}") }
            override fun onActivityDestroyed(a: Activity) { LifecycleLog.record("onDestroy ${name(a)} (finishing=${a.isFinishing}, config change=${a.isChangingConfigurations})") }
        })
    }
}

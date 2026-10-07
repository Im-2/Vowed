package app.vowed

import android.app.Application
import app.vowed.data.AccountRepository
import app.vowed.data.BackendApi
import app.vowed.data.Prefs
import app.vowed.proof.ProofEngine
import app.vowed.device.DeviceKey
import app.vowed.wallet.WalletManager

/** Hand-wired dependencies (no DI framework yet: the graph is small, and fewer moving parts means fewer build surprises). */
class AppContainer(val app: Application) {
    val prefs = Prefs(app)
    val api = BackendApi(baseUrl = { prefs.backendUrl })
    val wallet = WalletManager(prefs)
    val deviceKey: (String) -> DeviceKey = { DeviceKey(app, it) }
    val account = AccountRepository(this)
    val proofs = ProofEngine(api, { deviceKey(prefs.wallet ?: error("no wallet")) })
}

class VowedApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

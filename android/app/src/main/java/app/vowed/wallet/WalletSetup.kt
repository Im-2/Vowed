package app.vowed.wallet

/**
 * When to show "One quick step before connecting". The app cannot switch a wallet's network itself (no Mobile Wallet Adapter call does that),
 * so first-time users on the practice network (devnet) are told how, and anyone whose wallet turned the connection down for a network
 * reason sees it again. Once a connection has worked, experienced users never see it unless they open "Wallet setup help" in You.
 */
object WalletSetupLogic {
    enum class Reason {
        /** first time someone taps Connect wallet on a devnet build */
        First,
        /** the wallet said it does not support the network Vowed asked for */
        Mismatch,
        /** the wallet refused without saying why; it may be the network */
        PossibleMismatch,
        /** opened by hand from You */
        Help,
    }

    /**
     * The reason to show the screen instead of connecting, or null to connect straight away.
     * [lastFailure] is how the previous connection attempt failed, if it did.
     */
    fun reasonToShow(practiceNetwork: Boolean, everConnected: Boolean, lastFailure: WalletErrors.Kind?): Reason? = when {
        !practiceNetwork -> null
        lastFailure == WalletErrors.Kind.NetworkMismatch -> Reason.Mismatch
        lastFailure == WalletErrors.Kind.PossibleMismatch -> Reason.PossibleMismatch
        !everConnected -> Reason.First
        else -> null
    }

    /** True when the network the server reports is a practice network (anything but the real one). */
    fun isPracticeNetwork(serverNetwork: String?): Boolean = serverNetwork == null || serverNetwork.lowercase() !in setOf("mainnet", "mainnet-beta")

    const val PHANTOM_PACKAGE = "app.phantom"
    const val PHANTOM_PLAY_URL = "https://play.google.com/store/apps/details?id=app.phantom"

    fun headline(reason: Reason): String = when (reason) {
        Reason.First, Reason.Help -> "One quick step before connecting"
        Reason.Mismatch, Reason.PossibleMismatch -> "Your wallet needs one switch"
    }

    fun message(reason: Reason): String = when (reason) {
        Reason.First, Reason.Help ->
            "Vowed runs on a practice network (called devnet), so no real money is ever used. Your wallet has to be switched to it once."
        Reason.Mismatch -> "Your wallet is on the real network. Switch it to Testnet Mode and try again. Vowed uses a practice network with no real money."
        Reason.PossibleMismatch ->
            "Your wallet did not connect. If it is on the real network, switch it to Testnet Mode and try again. Vowed uses a practice network with no real money."
    }
}

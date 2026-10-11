package app.vowed.wallet

import app.vowed.data.KeyValue

/** The two ways a wallet can be asked to handle a transaction over Mobile Wallet Adapter. */
enum class SignMethod(val wire: String, val plain: String) {
    /** `sign_and_send_transactions`: the wallet signs AND sends it */
    SendBySelf("sign_and_send_transactions", "the wallet signs and sends it"),

    /** `sign_transactions`: the wallet only signs, Vowed hands the signed transaction to its server, which sends it */
    SignOnly("sign_transactions", "the wallet only signs, Vowed sends it"),
    ;

    fun other(): SignMethod = if (this == SendBySelf) SignOnly else SendBySelf

    companion object {
        fun fromWire(s: String?): SignMethod? = entries.firstOrNull { it.wire == s }
    }
}

/** What a wallet says it can do (`get_capabilities`), for the diagnostics and to pick a method for a wallet Vowed has not seen before. */
data class WalletCaps(
    /** null when the wallet did not say */
    val supportsSignAndSend: Boolean?,
    val maxTransactionsPerRequest: Int?,
    val transactionVersions: String,
    val optionalFeatures: String,
) {
    fun describe(): String =
        "sign_and_send_transactions: ${supportsSignAndSend ?: "not said"}, max transactions per request: ${maxTransactionsPerRequest ?: "not said"}, " +
            "transaction versions: ${transactionVersions.ifBlank { "not said" }}, optional features: ${optionalFeatures.ifBlank { "none listed" }}"
}

/**
 * What Vowed has learned about each wallet app (by package name): which of the two methods got an answer, and whether the wallet honours a saved
 * authorization token. Phantom, for example, refuses a saved token with error -1, so Vowed stops sending it and does a plain authorize in the same visit.
 */
class WalletMemory(private val kv: KeyValue) {
    fun method(pkg: String): SignMethod? = SignMethod.fromWire(kv.get("wallet_method_$pkg"))
    fun rememberMethod(pkg: String, m: SignMethod) = kv.put("wallet_method_$pkg", m.wire)

    /** false once this wallet has refused a saved authorization */
    fun tokenHonoured(pkg: String): Boolean = kv.get("wallet_token_refused_$pkg") != "1"
    fun refuseToken(pkg: String) = kv.put("wallet_token_refused_$pkg", "1")
}

/** Which method to start with: the one that was asked for (Try again uses the OTHER one), then what worked before, then what the wallet says it supports. */
object MethodChooser {
    fun choose(override: SignMethod?, remembered: SignMethod?, caps: WalletCaps?): SignMethod = override ?: remembered ?: when (caps?.supportsSignAndSend) {
        false -> SignMethod.SignOnly
        else -> SignMethod.SendBySelf
    }
}

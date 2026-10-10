package app.vowed.data

/**
 * The words for "your wallet cannot do this yet", chosen BEFORE the wallet is opened so a person is never sent to a wallet for a transaction that
 * is known to fail. Pure, so it is tested without a phone.
 */
object PreflightText {
    /** Which actions to offer: the faucet button helps when SOL or tokens are missing. */
    fun offersFaucet(p: Preflight): Boolean = p.reasons.any { it.code == "low_sol" || it.code == "no_token_account" || it.code == "low_tokens" }

    /**
     * One message for everything that is missing. [solGiftReceived] says whether this wallet already got the one-time devnet SOL gift (true), has not
     * (false), or whether that is not known (null): once the gift is gone, "Get test tokens" cannot bring more SOL, so the words change.
     */
    fun message(p: Preflight, solGiftReceived: Boolean?): String = p.reasons.joinToString(" ") { r ->
        if (r.code == "low_sol" && solGiftReceived == true) {
            "Your wallet needs a little devnet SOL for network fees: it has ${FaucetText.sol(p.sol.balance)} SOL and this step needs about ${FaucetText.sol(p.sol.needed)} SOL. " +
                "The free SOL is sent once per wallet and was already sent, so ask the project team or use Solana's devnet faucet (faucet.solana.com), then try again."
        } else {
            r.message
        }
    }
}

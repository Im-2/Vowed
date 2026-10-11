package app.vowed.data

/**
 * The words for "your wallet cannot do this yet", chosen BEFORE the wallet is opened so a person is never sent to a wallet for a transaction that
 * is known to fail. Pure, so it is tested without a phone.
 */
object PreflightText {
    /** Which actions to offer: the faucet button helps when SOL or tokens are missing. */
    fun offersFaucet(p: Preflight): Boolean = p.reasons.any { it.code == "low_sol" || it.code == "no_token_account" || it.code == "low_tokens" }

    /**
     * One message for everything that is missing. [canGetSol] says whether the faucet can still send SOL to this wallet (true), cannot (false), or
     * whether that is not known (null): when it cannot, "Get test SOL" would bring nothing, so the words change.
     */
    fun message(p: Preflight, canGetSol: Boolean?): String = p.reasons.joinToString(" ") { r ->
        if (r.code == "low_sol") {
            val base = "Your wallet needs about ${SolText.sol(p.sol.needed)} SOL on devnet for fees and rent. You have ${SolText.sol(p.sol.balance)} SOL."
            if (canGetSol == false) "$base The free test SOL for this wallet is used up for now: ask the project team, or use Solana's devnet faucet (faucet.solana.com), then try again." else "$base Tap Get test SOL."
        } else {
            r.message
        }
    }

    /** The button: "Get test SOL" when only SOL is missing, "Get test tokens" otherwise. */
    fun buttonLabel(p: Preflight): String = if (p.reasons.all { it.code == "low_sol" }) "Get test SOL" else "Get test tokens"
}

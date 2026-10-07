package app.vowed.core

/** Fixed program ids and instruction layouts. Checked against the IDL by a unit test (programs/vowed/idl/vowed.json). */
object Programs {
    /** The deployed Vowed program (devnet). Not a secret; changing it requires a new app release. */
    const val VOWED = "BMTXJRZ4QxzCg4UCHKo6qGGiGXKW26ARPAtPaXA8k7EL"
    const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val ASSOCIATED_TOKEN_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    const val SYSTEM_PROGRAM = "11111111111111111111111111111111"

    val CREATE_POOL = byteArrayOf(233.toByte(), 146.toByte(), 209.toByte(), 142.toByte(), 207.toByte(), 104, 64, 188.toByte())
    val JOIN_POOL = byteArrayOf(14, 65, 62, 16, 116, 17, 195.toByte(), 107)
    val CLAIM = byteArrayOf(62, 198.toByte(), 214.toByte(), 193.toByte(), 213.toByte(), 159.toByte(), 108, 210.toByte())

    val CREATE_POOL_ACCOUNTS = listOf("creator", "config", "pool", "mint", "vault", "token_program", "system_program")
    val JOIN_POOL_ACCOUNTS = listOf("user", "config", "pool", "participation", "mint", "vault", "user_token", "token_program", "system_program")
    val CLAIM_ACCOUNTS = listOf("owner", "pool", "participation", "mint", "vault", "owner_token", "token_program")
}

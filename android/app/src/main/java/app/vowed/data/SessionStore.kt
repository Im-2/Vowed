package app.vowed.data

import app.vowed.letters.LetterCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.Base64

/** Where the encrypted session lives. The app uses SharedPreferences; tests use a map. */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** A backend session: the token and when it stops working. */
@Serializable
data class Session(val wallet: String, val token: String, val expiresAt: Long)

/**
 * Keeps the backend session across app restarts so a restart does not send the person back to their wallet until the session really ends.
 * The token is AES-256-GCM encrypted with a key that lives in the Android Keystore and never leaves it (see [LetterCipher]); what is written
 * to disk is only the ciphertext. A session that is expired, belongs to another wallet, was changed, or cannot be decrypted (for example
 * after a backup restore on another phone) is wiped and treated as "signed out".
 */
class SessionStore(private val kv: KeyValue, private val cipher: LetterCipher, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    fun save(session: Session) {
        val plain = AppJson.encodeToString(session).encodeToByteArray()
        kv.put(KEY, Base64.getEncoder().encodeToString(cipher.encrypt(plain)))
    }

    fun load(wallet: String): Session? {
        val stored = kv.get(KEY) ?: return null
        val session = try {
            AppJson.decodeFromString<Session>(cipher.decrypt(Base64.getDecoder().decode(stored)).decodeToString())
        } catch (e: Exception) {
            clear()
            return null
        }
        if (session.wallet != wallet || session.expiresAt <= now()) {
            clear()
            return null
        }
        return session
    }

    fun clear() = kv.put(KEY, null)

    private companion object {
        const val KEY = "session_v1"
    }
}

/**
 * Holds the current session and keeps it alive quietly: [refreshIfNeeded] swaps a token that is about to run out for a new one (the server
 * allows this for 7 days after the wallet signed in). Only a refusal from the server ends the session; a missing network never does.
 */
class SessionKeeper(
    private val store: SessionStore,
    private val setToken: (String?) -> Unit,
    private val refreshCall: suspend () -> VerifyResponse,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** refresh when less than this many seconds are left */
    private val refreshWithin: Long = REFRESH_WITHIN_SECS,
) {
    enum class Outcome { NotNeeded, Refreshed, Expired, Offline }

    var current: Session? = null
        private set

    /** Brings back the saved session for [wallet] after a restart. True when the phone is signed in again without the wallet. */
    fun restore(wallet: String?): Boolean {
        val s = wallet?.let { runCatching { store.load(it) }.getOrNull() }
        current = s
        setToken(s?.token)
        return s != null
    }

    /** Called after the wallet signed in. */
    fun adopt(r: VerifyResponse) {
        val s = Session(r.wallet, r.token, r.expiresAt)
        current = s
        setToken(s.token)
        // saving is a convenience: if the phone cannot encrypt or write it, the person is still signed in for this run
        runCatching { store.save(s) }
    }

    suspend fun refreshIfNeeded(): Outcome {
        val s = current ?: return Outcome.Expired
        if (s.expiresAt <= now()) return forgetAs(Outcome.Expired)
        if (s.expiresAt - now() > refreshWithin) return Outcome.NotNeeded
        return try {
            adopt(refreshCall())
            Outcome.Refreshed
        } catch (e: ApiException) {
            if (e.status == 401) forgetAs(Outcome.Expired) else Outcome.Offline
        } catch (e: Exception) {
            Outcome.Offline
        }
    }

    /** The server said the token is no good (401): forget it so the app asks for a fresh sign-in. */
    fun expire() { forgetAs(Outcome.Expired) }

    fun forget() { forgetAs(Outcome.Expired) }

    private fun forgetAs(o: Outcome): Outcome {
        current = null
        setToken(null)
        store.clear()
        return o
    }

    companion object {
        const val REFRESH_WITHIN_SECS = 3 * 3_600L
    }
}

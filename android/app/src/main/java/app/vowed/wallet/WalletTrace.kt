package app.vowed.wallet

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One Android lifecycle event of the app, remembered in memory for the diagnostics. */
data class LifecycleEvent(val atMs: Long, val text: String)

/**
 * The last few Android lifecycle events (onCreate, onResume, onPause, onStop, onDestroy ...) of Vowed's activities, plus when the process started
 * and how many activities were created since. Read by [WalletTrace] so a wallet problem can be placed on the same timeline as "Vowed went to the
 * background" or "Vowed was restarted while the wallet was open". Nothing here leaves the phone unless the person taps "Copy diagnostics".
 */
object LifecycleLog {
    private const val MAX = 80
    private val events = ArrayDeque<LifecycleEvent>()

    /** When this process started (wall clock, milliseconds); 0 until [markProcessStart] is called. */
    @Volatile var processStartMs: Long = 0
        private set
    @Volatile var activitiesCreated: Int = 0
        private set

    fun markProcessStart(nowMs: Long) { processStartMs = nowMs }

    @Synchronized fun record(text: String, nowMs: Long = System.currentTimeMillis()) {
        if (text.startsWith("onCreate")) activitiesCreated++
        events.addLast(LifecycleEvent(nowMs, text))
        while (events.size > MAX) events.removeFirst()
    }

    @Synchronized fun snapshot(): List<LifecycleEvent> = events.toList()

    @Synchronized fun clearForTests() { events.clear(); activitiesCreated = 0; processStartMs = 0 }
}

/**
 * A timeline of one wallet connection attempt, for the "Details" under an error and the "Copy diagnostics" button.
 * It must never contain a key, a signature or a token: every line goes through [scrub], which shortens public addresses to "AbCd…WxYz" and
 * replaces anything long that could be a signature, a token or a key with "[redacted]". Callers also only pass plain facts (names, counts, results).
 */
class WalletTrace(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val lifecycle: () -> List<LifecycleEvent> = { LifecycleLog.snapshot() },
) {
    private val startedAt = clock()
    private val entries = ArrayList<LifecycleEvent>()

    @Synchronized fun add(event: String, detail: String? = null) {
        entries.add(LifecycleEvent(clock(), scrub(if (detail.isNullOrBlank()) event else "$event: $detail")))
    }

    @Synchronized fun isEmpty() = entries.isEmpty()

    /** The timeline as text. [header] lines (app version, phone, wallets that were found) come first. */
    @Synchronized fun render(header: List<String> = emptyList()): String {
        val end = entries.lastOrNull()?.atMs ?: clock()
        val out = StringBuilder()
        out.appendLine("Vowed wallet diagnostics (no keys, signatures or tokens)")
        for (h in header) out.appendLine(scrub(h))
        val ps = LifecycleLog.processStartMs
        if (ps > 0) out.appendLine("process started ${time(ps)}; activities created since: ${LifecycleLog.activitiesCreated}")
        out.appendLine("attempt started ${time(startedAt)}")
        val merged = entries.map { it to "wallet" } +
            lifecycle().filter { it.atMs >= startedAt - 3_000 && it.atMs <= end + 15_000 }.map { it to "app" }
        for ((e, kind) in merged.sortedBy { it.first.atMs }) {
            out.appendLine("+${"%.3f".format(Locale.US, (e.atMs - startedAt) / 1000.0)} s [$kind] ${e.text}")
        }
        out.appendLine("total ${"%.1f".format(Locale.US, (end - startedAt) / 1000.0)} s")
        out.appendLine("note: the wallet's close code is not exposed by the Mobile Wallet Adapter library; the close reason above is the library's exception text")
        return out.toString().trimEnd()
    }

    private fun time(ms: Long) = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))

    companion object {
        private const val B58 = "[1-9A-HJ-NP-Za-km-z]"
        private val jwt = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}")
        private val longB58 = Regex("$B58{60,}")
        private val address = Regex("$B58{32,44}")
        private val longB64 = Regex("[A-Za-z0-9+/_=-]{48,}")

        /** "2YePEWRp8aTfqQnJHK2EBt4YkXRXWetzdmL8dDG7UFZf" becomes "2YeP…UFZf". */
        fun shortAddress(a: String): String = if (a.length > 9) "${a.take(4)}…${a.takeLast(4)}" else a

        /** Removes anything that must not be copied around: tokens, signatures and long keys vanish, addresses are shortened. */
        fun scrub(text: String): String = text
            .replace(jwt, "[redacted]")
            .replace(longB58, "[redacted]")
            .replace(address) { shortAddress(it.value) }
            .replace(longB64, "[redacted]")
    }
}

/** Holds the trace of the connection attempt in progress (or the last one) so the error screen can show it. */
object WalletDiagnostics {
    @Volatile var current: WalletTrace = WalletTrace()
        private set

    /** Lines for the top of the diagnostics: app version, phone, wallets found. Set once by the app. */
    @Volatile var header: () -> List<String> = { emptyList() }

    fun begin(): WalletTrace = WalletTrace().also { current = it }

    fun render(): String = current.render(header())
}

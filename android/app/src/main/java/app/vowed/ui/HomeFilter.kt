package app.vowed.ui

import app.vowed.data.Challenge
import app.vowed.data.ExploreItem
import app.vowed.ui.components.CategoryStyle

/** The lists on Home: what is running now, and what is finished (shown collapsed under "Past challenges"). */
data class HomeLists(val active: List<Challenge>, val past: List<Challenge>)

/**
 * Which of my challenges Home shows. Pure, so the rules are tested without a phone. Nothing here touches the chain or the backend: a
 * pool that is hidden is simply not listed.
 *  - a pool with no readable title (no plan title, or one that is only an address) is not shown at all;
 *  - a pool with no participants and an empty pot is not shown at all (an abandoned create, or a test run);
 *  - a pool I neither created nor joined is not shown (when the joined status is known);
 *  - a pool on the phone's hidden list (debug builds) is not shown;
 *  - Open and not yet ended: active; Settling, Settled, Voided, or Open but past its end: past.
 */
object HomeFilter {
    private val BASE58 = "[1-9A-HJ-NP-Za-km-z]"
    private val shortAddress = Regex("""^(Challenge\s+)?$BASE58{3,8}(\.{2,3}|…)$BASE58{3,8}$""")
    private val fullAddress = Regex("""^$BASE58{32,44}$""")

    /** True for text that is only a pool address, short or full ("Challenge 6yXB...PYWb"). */
    fun looksLikeAddress(text: String): Boolean = text.trim().let { shortAddress.matches(it) || fullAddress.matches(it) }

    /** The plan title, or null when there is none worth showing. */
    fun readableTitle(c: Challenge): String? {
        val t = c.plan?.get("title")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.trim()
        return t?.takeIf { it.isNotEmpty() && !looksLikeAddress(it) }
    }

    private fun potIsEmpty(c: Challenge): Boolean = (c.totalDeposits.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO).signum() == 0

    fun isFinished(c: Challenge, nowSec: Long): Boolean = c.status != "Open" || c.endTs <= nowSec

    /**
     * [participantsOf] gives the wallets of a pool when the app has loaded its detail, or null when unknown (then the server's "mine" list is trusted).
     */
    fun split(
        all: List<Challenge>,
        myWallet: String?,
        nowSec: Long,
        hidden: Set<String> = emptySet(),
        participantsOf: (String) -> Set<String>? = { null },
    ): HomeLists {
        val shown = all.filter { c ->
            c.pool !in hidden &&
                readableTitle(c) != null &&
                !(c.participantCount == 0 && potIsEmpty(c)) &&
                (myWallet == null || c.creator == myWallet || participantsOf(c.pool)?.contains(myWallet) != false)
        }
        val (past, active) = shown.partition { isFinished(it, nowSec) }
        return HomeLists(active, past.sortedByDescending { it.endTs })
    }

    /** A name for an Explore item: its title, or "<Category> challenge" when the title is missing or only an address. Never an address. */
    fun exploreTitle(p: ExploreItem): String = p.title.trim().takeIf { it.isNotEmpty() && !looksLikeAddress(it) } ?: "${CategoryStyle.label(p.category)} challenge"

    /** A name for a challenge on screens that must always show something (detail, freeze options): the title, or a category name. */
    fun displayTitle(c: Challenge): String = readableTitle(c) ?: "${CategoryStyle.label(planCategory(c))} challenge"
}
